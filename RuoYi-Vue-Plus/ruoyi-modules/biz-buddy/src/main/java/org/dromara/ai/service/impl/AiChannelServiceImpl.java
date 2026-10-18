package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.channel.AiChannelStreamStatus;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.domain.AiChannelIdentity;
import org.dromara.ai.domain.AiChannelThread;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiChannelBo;
import org.dromara.ai.domain.bo.AiChannelIdentityBo;
import org.dromara.ai.domain.vo.AiChannelIdentityVo;
import org.dromara.ai.domain.vo.AiChannelThreadVo;
import org.dromara.ai.domain.vo.AiChannelVo;
import org.dromara.ai.mapper.AiChannelIdentityMapper;
import org.dromara.ai.mapper.AiChannelMapper;
import org.dromara.ai.mapper.AiChannelThreadMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 渠道接入 Service 业务层处理（REQ-AI-24）
 *
 * <p>凭据列统一以 AES 密文落库（{@link AiSecretManager}），页面与接口均不回显明文；
 * 仅回调链路装配时解密为运行期配置（{@link #loadRuntimeConfig(String)}）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiChannelServiceImpl implements IAiChannelService {

    /**
     * 企业微信 EncodingAESKey 长度（43 位 Base64，无填充）
     */
    private static final int WECOM_AES_KEY_LENGTH = 43;

    private final AiChannelMapper baseMapper;

    private final AiChannelIdentityMapper identityMapper;

    private final AiChannelThreadMapper threadMapper;

    private final AiSessionMapper sessionMapper;

    private final IAiAgentService agentService;

    private final AiSecretManager secretManager;

    private final AiChannelStreamStatus streamStatus;

    private final UserService userService;

    @Override
    public PageResult<AiChannelVo> queryPageList(AiChannelBo bo, PageQuery pageQuery) {
        Page<AiChannelVo> result = baseMapper.selectVoPage(pageQuery.build(), buildChannelQuery(bo));
        fillChannelDisplay(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiChannelVo queryById(Long id) {
        AiChannelVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("渠道不存在或已被删除");
        }
        fillChannelDisplay(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiChannelBo bo) {
        AiChannel add = MapstructUtils.convert(bo, AiChannel.class);
        add.setChannelName(StringUtils.trim(bo.getChannelName()));
        add.setChannelCode(StringUtils.trim(bo.getChannelCode()));
        add.setPlatformType(normalizePlatformType(bo.getPlatformType()));
        add.setMode(normalizeMode(bo.getMode()));
        add.setApiBase(StringUtils.trim(bo.getApiBase()));
        add.setPlatformAgentId(StringUtils.trim(bo.getPlatformAgentId()));
        add.setAppId(StringUtils.trim(bo.getAppId()));
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(AiChannel.STATUS_ENABLED);
        }
        // 明文凭据加密落库
        add.setAppSecret(secretManager.encrypt(bo.getAppSecret()));
        add.setEncryptKey(secretManager.encrypt(bo.getEncryptKey()));
        add.setVerificationToken(secretManager.encrypt(bo.getVerificationToken()));
        add.setWebhookSecret(secretManager.encrypt(bo.getWebhookSecret()));
        add.setApiToken(secretManager.encrypt(bo.getApiToken()));
        validChannelCodeUnique(add);
        applyValidated(add, bo);
        return baseMapper.insert(add) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiChannelBo bo) {
        AiChannel exists = requireChannel(bo.getId());
        AiChannel update = MapstructUtils.convert(bo, AiChannel.class);
        update.setChannelName(StringUtils.trim(bo.getChannelName()));
        update.setChannelCode(StringUtils.trim(bo.getChannelCode()));
        update.setPlatformType(normalizePlatformType(bo.getPlatformType()));
        update.setMode(normalizeMode(bo.getMode()));
        update.setApiBase(StringUtils.trim(bo.getApiBase()));
        update.setPlatformAgentId(StringUtils.trim(bo.getPlatformAgentId()));
        update.setAppId(StringUtils.trim(bo.getAppId()));
        // 凭据留空表示保持原值（页面不回显明文）
        update.setAppSecret(keepOrEncrypt(bo.getAppSecret(), exists.getAppSecret()));
        update.setEncryptKey(keepOrEncrypt(bo.getEncryptKey(), exists.getEncryptKey()));
        update.setVerificationToken(keepOrEncrypt(bo.getVerificationToken(), exists.getVerificationToken()));
        update.setWebhookSecret(keepOrEncrypt(bo.getWebhookSecret(), exists.getWebhookSecret()));
        update.setApiToken(keepOrEncrypt(bo.getApiToken(), exists.getApiToken()));
        validChannelCodeUnique(update);
        applyValidated(update, bo);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的渠道");
        }
        if (baseMapper.selectByIds(ids).size() != ids.size()) {
            throw new ServiceException("渠道不存在或已被删除");
        }
        // 关联映射随渠道一并清理：身份映射逻辑删除，线程映射物理删除
        identityMapper.delete(Wrappers.<AiChannelIdentity>lambdaQuery().in(AiChannelIdentity::getChannelId, ids));
        threadMapper.delete(Wrappers.<AiChannelThread>lambdaQuery().in(AiChannelThread::getChannelId, ids));
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public AiChannel loadRuntimeConfig(String channelCode) {
        if (StringUtils.isBlank(channelCode)) {
            return null;
        }
        LambdaQueryWrapper<AiChannel> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChannel::getChannelCode, channelCode);
        lqw.eq(AiChannel::getStatus, AiChannel.STATUS_ENABLED);
        lqw.last("limit 1");
        AiChannel channel = baseMapper.selectOne(lqw);
        if (channel == null) {
            return null;
        }
        // 只回传运行期所需字段，凭据解密为明文（不落库、不回显）
        AiChannel runtime = new AiChannel();
        runtime.setId(channel.getId());
        runtime.setChannelCode(channel.getChannelCode());
        runtime.setPlatformType(channel.getPlatformType());
        runtime.setMode(channel.getMode());
        runtime.setAgentId(channel.getAgentId());
        runtime.setAppId(channel.getAppId());
        runtime.setAppSecret(secretManager.decrypt(channel.getAppSecret()));
        runtime.setPlatformAgentId(channel.getPlatformAgentId());
        runtime.setEncryptKey(secretManager.decrypt(channel.getEncryptKey()));
        runtime.setVerificationToken(secretManager.decrypt(channel.getVerificationToken()));
        runtime.setWebhookSecret(secretManager.decrypt(channel.getWebhookSecret()));
        runtime.setApiToken(secretManager.decrypt(channel.getApiToken()));
        runtime.setApiBase(channel.getApiBase());
        runtime.setUpdateTime(channel.getUpdateTime());
        return runtime;
    }

    @Override
    public List<String> listEnabledLongConnectionChannelCodes() {
        LambdaQueryWrapper<AiChannel> lqw = Wrappers.lambdaQuery();
        lqw.select(AiChannel::getChannelCode);
        lqw.eq(AiChannel::getStatus, AiChannel.STATUS_ENABLED);
        // 钉钉恒为长连接；飞书仅 mode=ws 时长连接（其余为 Webhook，由外部平台回调驱动）
        lqw.and(w -> w.eq(AiChannel::getPlatformType, AiChannel.PLATFORM_DINGTALK)
            .or(f -> f.eq(AiChannel::getPlatformType, AiChannel.PLATFORM_FEISHU)
                .eq(AiChannel::getMode, AiChannel.MODE_WS)));
        return baseMapper.selectList(lqw).stream().map(AiChannel::getChannelCode).toList();
    }

    // -----------------------------------------------------------------
    //  外部身份映射
    // -----------------------------------------------------------------

    @Override
    public PageResult<AiChannelIdentityVo> queryIdentityPageList(AiChannelIdentityBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiChannelIdentity> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getChannelId() != null, AiChannelIdentity::getChannelId, bo.getChannelId());
        lqw.like(StringUtils.isNotBlank(bo.getKeyword()), AiChannelIdentity::getExternalUserId, bo.getKeyword());
        lqw.orderByDesc(AiChannelIdentity::getCreateTime);
        Page<AiChannelIdentityVo> result = identityMapper.selectVoPage(pageQuery.build(), lqw);
        fillIdentityDisplay(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public Boolean insertIdentity(AiChannelIdentityBo bo) {
        AiChannelIdentity add = MapstructUtils.convert(bo, AiChannelIdentity.class);
        add.setExternalUserId(StringUtils.trim(bo.getExternalUserId()));
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(AiChannelIdentity.STATUS_ENABLED);
        }
        requireChannel(add.getChannelId());
        validIdentityUnique(add);
        validPlatformUser(add.getPlatformUserId());
        return identityMapper.insert(add) > 0;
    }

    @Override
    public Boolean updateIdentity(AiChannelIdentityBo bo) {
        AiChannelIdentity exists = identityMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("身份映射不存在或已被删除");
        }
        AiChannelIdentity update = MapstructUtils.convert(bo, AiChannelIdentity.class);
        update.setExternalUserId(StringUtils.trim(bo.getExternalUserId()));
        requireChannel(update.getChannelId());
        validIdentityUnique(update);
        validPlatformUser(update.getPlatformUserId());
        return identityMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteIdentities(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的身份映射");
        }
        return identityMapper.deleteByIds(ids) > 0;
    }

    @Override
    public Long resolvePlatformUserId(Long channelId, String externalUserId) {
        if (channelId == null || StringUtils.isBlank(externalUserId)) {
            return null;
        }
        LambdaQueryWrapper<AiChannelIdentity> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChannelIdentity::getChannelId, channelId);
        lqw.eq(AiChannelIdentity::getExternalUserId, externalUserId);
        lqw.eq(AiChannelIdentity::getStatus, AiChannelIdentity.STATUS_ENABLED);
        lqw.last("limit 1");
        AiChannelIdentity identity = identityMapper.selectOne(lqw);
        return identity == null ? null : identity.getPlatformUserId();
    }

    // -----------------------------------------------------------------
    //  消息线程映射
    // -----------------------------------------------------------------

    @Override
    public PageResult<AiChannelThreadVo> queryThreadPageList(Long channelId, PageQuery pageQuery) {
        LambdaQueryWrapper<AiChannelThread> lqw = Wrappers.lambdaQuery();
        lqw.eq(channelId != null, AiChannelThread::getChannelId, channelId);
        lqw.orderByDesc(AiChannelThread::getLastActiveTime);
        Page<AiChannelThreadVo> result = threadMapper.selectVoPage(pageQuery.build(), lqw);
        fillThreadDisplay(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiChannelThread findThread(Long channelId, String threadKey) {
        LambdaQueryWrapper<AiChannelThread> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChannelThread::getChannelId, channelId);
        lqw.eq(AiChannelThread::getThreadKey, threadKey);
        lqw.last("limit 1");
        return threadMapper.selectOne(lqw);
    }

    @Override
    public void bindThread(Long channelId, String threadKey, Long sessionId) {
        AiChannelThread thread = new AiChannelThread();
        thread.setChannelId(channelId);
        thread.setThreadKey(threadKey);
        thread.setSessionId(sessionId);
        thread.setCreateTime(LocalDateTime.now());
        thread.setLastActiveTime(LocalDateTime.now());
        threadMapper.insert(thread);
    }

    @Override
    public void touchThread(Long channelId, String threadKey) {
        AiChannelThread update = new AiChannelThread();
        update.setLastActiveTime(LocalDateTime.now());
        threadMapper.update(update, Wrappers.<AiChannelThread>lambdaUpdate()
            .eq(AiChannelThread::getChannelId, channelId)
            .eq(AiChannelThread::getThreadKey, threadKey));
    }

    @Override
    public Boolean deleteThreads(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的会话映射");
        }
        return threadMapper.deleteByIds(ids) > 0;
    }

    // -----------------------------------------------------------------
    //  校验与填充
    // -----------------------------------------------------------------

    /**
     * 归一化平台类型（Webhook 三平台 + 钉钉长连接）。
     *
     * @param platformType 平台类型
     * @return 归一化后的平台类型
     */
    private String normalizePlatformType(String platformType) {
        String type = StringUtils.trim(platformType);
        if (!AiChannel.PLATFORM_FEISHU.equals(type)
            && !AiChannel.PLATFORM_WECOM.equals(type)
            && !AiChannel.PLATFORM_GITHUB.equals(type)
            && !AiChannel.PLATFORM_DINGTALK.equals(type)) {
            throw new ServiceException("平台类型仅支持 feishu / wecom / github / dingtalk");
        }
        return type;
    }

    /**
     * 归一化接入形态：留空默认 Webhook；仅接受 webhook / ws。
     *
     * @param mode 接入形态
     * @return 归一化后的接入形态
     */
    private String normalizeMode(String mode) {
        String value = StringUtils.trim(mode);
        if (StringUtils.isBlank(value)) {
            return AiChannel.MODE_WEBHOOK;
        }
        if (!AiChannel.MODE_WEBHOOK.equals(value) && !AiChannel.MODE_WS.equals(value)) {
            throw new ServiceException("接入形态仅支持 webhook / ws");
        }
        return value;
    }

    /**
     * 校验渠道编码唯一（逻辑删除下由服务层保证）。
     *
     * @param entity 待保存实体
     */
    private void validChannelCodeUnique(AiChannel entity) {
        LambdaQueryWrapper<AiChannel> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChannel::getChannelCode, entity.getChannelCode());
        lqw.ne(entity.getId() != null, AiChannel::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("渠道编码已存在：" + entity.getChannelCode());
        }
    }

    /**
     * 校验智能体可用性与平台必填凭据。
     *
     * @param entity 待保存实体（凭据为密文，仅判空）
     * @param bo     业务对象（明文凭据，用于格式校验）
     */
    private void applyValidated(AiChannel entity, AiChannelBo bo) {
        AiAgent agent = agentService.getEnabledAgent(entity.getAgentId());
        if (agent == null) {
            throw new ServiceException("默认智能体不存在或已停用");
        }
        switch (entity.getPlatformType()) {
            case AiChannel.PLATFORM_FEISHU -> {
                requireText(entity.getAppId(), "飞书 appId 不能为空");
                requireText(entity.getAppSecret(), "飞书 appSecret 不能为空");
            }
            case AiChannel.PLATFORM_WECOM -> {
                requireText(entity.getAppId(), "企业微信 corpId 不能为空");
                requireText(entity.getAppSecret(), "企业微信 secret 不能为空");
                requireText(entity.getPlatformAgentId(), "企业微信 agentId 不能为空");
                requirePositiveInt(entity.getPlatformAgentId(), "企业微信 agentId 必须为正整数");
                requireText(entity.getVerificationToken(), "企业微信 Token 不能为空");
                requireText(entity.getEncryptKey(), "企业微信 EncodingAESKey 不能为空");
                if (StringUtils.isNotBlank(bo.getEncryptKey())
                    && bo.getEncryptKey().length() != WECOM_AES_KEY_LENGTH) {
                    throw new ServiceException("企业微信 EncodingAESKey 必须为 43 位字符");
                }
            }
            case AiChannel.PLATFORM_GITHUB -> {
                requireText(entity.getWebhookSecret(), "GitHub Webhook Secret 不能为空");
                requireText(entity.getApiToken(), "GitHub 访问令牌（PAT）不能为空");
            }
            case AiChannel.PLATFORM_DINGTALK -> {
                requireText(entity.getAppId(), "钉钉 AppKey 不能为空");
                requireText(entity.getAppSecret(), "钉钉 AppSecret 不能为空");
                requireText(entity.getPlatformAgentId(), "钉钉机器人编码 RobotCode 不能为空");
            }
            default -> throw new ServiceException("平台类型仅支持 feishu / wecom / github / dingtalk");
        }
    }

    /**
     * 校验外部身份映射唯一（同渠道下外部用户标识唯一）。
     *
     * @param entity 待保存实体
     */
    private void validIdentityUnique(AiChannelIdentity entity) {
        LambdaQueryWrapper<AiChannelIdentity> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChannelIdentity::getChannelId, entity.getChannelId());
        lqw.eq(AiChannelIdentity::getExternalUserId, entity.getExternalUserId());
        lqw.ne(entity.getId() != null, AiChannelIdentity::getId, entity.getId());
        if (identityMapper.selectCount(lqw) > 0) {
            throw new ServiceException("该渠道下外部用户标识已存在：" + entity.getExternalUserId());
        }
    }

    /**
     * 校验映射到的平台用户存在。
     *
     * @param platformUserId 平台用户主键
     */
    private void validPlatformUser(Long platformUserId) {
        UserDTO user = platformUserId == null ? null : userService.selectById(platformUserId);
        if (user == null) {
            throw new ServiceException("映射的平台用户不存在");
        }
    }

    /**
     * 查询渠道实体（不存在时抛业务异常）。
     *
     * @param id 主键
     * @return 渠道实体
     */
    private AiChannel requireChannel(Long id) {
        AiChannel channel = id == null ? null : baseMapper.selectById(id);
        if (channel == null) {
            throw new ServiceException("渠道不存在或已被删除");
        }
        return channel;
    }

    /**
     * 凭据留空保持原值，否则加密新值。
     *
     * @param plain      明文入参
     * @param currentCipher 现网密文
     * @return 落库密文
     */
    private String keepOrEncrypt(String plain, String currentCipher) {
        return StringUtils.isBlank(plain) ? currentCipher : secretManager.encrypt(plain);
    }

    private void requireText(String value, String message) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException(message);
        }
    }

    private void requirePositiveInt(String value, String message) {
        try {
            if (Integer.parseInt(StringUtils.trim(value)) <= 0) {
                throw new ServiceException(message);
            }
        } catch (NumberFormatException e) {
            throw new ServiceException(message);
        }
    }

    /**
     * 构建渠道动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiChannel> buildChannelQuery(AiChannelBo bo) {
        LambdaQueryWrapper<AiChannel> lqw = Wrappers.lambdaQuery();
        String keyword = StringUtils.trim(bo.getKeyword());
        if (StringUtils.isNotBlank(keyword)) {
            lqw.and(w -> w.like(AiChannel::getChannelName, keyword).or().like(AiChannel::getChannelCode, keyword));
        }
        String platformType = StringUtils.trim(
            StringUtils.isNotBlank(bo.getPlatformTypeQuery()) ? bo.getPlatformTypeQuery() : bo.getPlatformType());
        lqw.eq(StringUtils.isNotBlank(platformType), AiChannel::getPlatformType, platformType);
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiChannel::getStatus, bo.getStatus());
        lqw.orderByDesc(AiChannel::getCreateTime);
        return lqw;
    }

    /**
     * 填充渠道列表的智能体名称 / 状态（凭据只给「是否已配置」布尔位）。
     *
     * @param list 渠道列表
     */
    private void fillChannelDisplay(List<AiChannelVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> agentIds = new HashSet<>();
        Set<Long> channelIds = new HashSet<>();
        for (AiChannelVo vo : list) {
            if (vo.getAgentId() != null) {
                agentIds.add(vo.getAgentId());
            }
            channelIds.add(vo.getId());
        }
        Map<Long, AiAgent> agents = Map.of();
        if (!agentIds.isEmpty()) {
            agents = agentService.queryEntities(agentIds).stream()
                .collect(java.util.stream.Collectors.toMap(AiAgent::getId, a -> a, (a, b) -> a));
        }
        // 凭据是否已配置（密文非空即视为已配置）
        Map<Long, AiChannel> rawMap = baseMapper.selectByIds(channelIds).stream()
            .collect(java.util.stream.Collectors.toMap(AiChannel::getId, c -> c, (a, b) -> a));
        for (AiChannelVo vo : list) {
            AiAgent agent = agents.get(vo.getAgentId());
            if (agent != null) {
                vo.setAgentName(agent.getAgentName());
                vo.setAgentStatus(agent.getStatus());
            }
            AiChannel raw = rawMap.get(vo.getId());
            vo.setAppSecretSet(raw != null && StringUtils.isNotBlank(raw.getAppSecret()));
            vo.setEncryptKeySet(raw != null && StringUtils.isNotBlank(raw.getEncryptKey()));
            vo.setVerificationTokenSet(raw != null && StringUtils.isNotBlank(raw.getVerificationToken()));
            vo.setWebhookSecretSet(raw != null && StringUtils.isNotBlank(raw.getWebhookSecret()));
            vo.setApiTokenSet(raw != null && StringUtils.isNotBlank(raw.getApiToken()));
            // 长连接渠道（钉钉 / 飞书 mode=ws）额外回显「是否已拉起」（Webhook 平台无此列）
            if (raw != null
                && (AiChannel.PLATFORM_DINGTALK.equals(raw.getPlatformType())
                    || (AiChannel.PLATFORM_FEISHU.equals(raw.getPlatformType())
                        && AiChannel.MODE_WS.equals(raw.getMode())))) {
                AiChannelStreamStatus.Snapshot snapshot = streamStatus.get(raw.getChannelCode());
                vo.setStreamStatus(snapshot == null ? AiChannelStreamStatus.STOPPED : snapshot.status());
                vo.setStreamStatusDetail(snapshot == null ? null : snapshot.detail());
            }
        }
    }

    /**
     * 填充身份映射的渠道名称与平台用户昵称。
     *
     * @param list 身份映射列表
     */
    private void fillIdentityDisplay(List<AiChannelIdentityVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> channelIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AiChannelIdentityVo vo : list) {
            if (vo.getChannelId() != null) {
                channelIds.add(vo.getChannelId());
            }
            if (vo.getPlatformUserId() != null) {
                userIds.add(vo.getPlatformUserId());
            }
        }
        Map<Long, String> channelNames = channelIds.isEmpty() ? Map.of() : baseMapper.selectByIds(channelIds).stream()
            .collect(java.util.stream.Collectors.toMap(AiChannel::getId, AiChannel::getChannelName, (a, b) -> a));
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        for (AiChannelIdentityVo vo : list) {
            vo.setChannelName(channelNames.get(vo.getChannelId()));
            vo.setPlatformUserName(userNames.get(vo.getPlatformUserId()));
        }
    }

    /**
     * 填充线程映射的渠道名称与会话标题 / 状态。
     *
     * @param list 线程映射列表
     */
    private void fillThreadDisplay(List<AiChannelThreadVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> channelIds = new HashSet<>();
        Set<Long> sessionIds = new HashSet<>();
        for (AiChannelThreadVo vo : list) {
            if (vo.getChannelId() != null) {
                channelIds.add(vo.getChannelId());
            }
            if (vo.getSessionId() != null) {
                sessionIds.add(vo.getSessionId());
            }
        }
        Map<Long, String> channelNames = channelIds.isEmpty() ? Map.of() : baseMapper.selectByIds(channelIds).stream()
            .collect(java.util.stream.Collectors.toMap(AiChannel::getId, AiChannel::getChannelName, (a, b) -> a));
        Map<Long, AiSession> sessions = sessionIds.isEmpty() ? Map.of()
            : sessionMapper.selectByIds(sessionIds).stream()
                .collect(java.util.stream.Collectors.toMap(AiSession::getId, s -> s, (a, b) -> a));
        for (AiChannelThreadVo vo : list) {
            vo.setChannelName(channelNames.get(vo.getChannelId()));
            AiSession session = sessions.get(vo.getSessionId());
            if (session != null) {
                vo.setSessionTitle(session.getSessionTitle());
                vo.setSessionStatus(session.getStatus());
            }
        }
    }

}