package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiAgentKb;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiAgentToolTrust;
import org.dromara.ai.domain.AiEntryAgent;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiAgentToolBo;
import org.dromara.ai.domain.bo.AiEntryAgentBo;
import org.dromara.ai.domain.bo.AiExpertAgentBo;
import org.dromara.ai.domain.vo.AiAgentToolTrustVo;
import org.dromara.ai.domain.vo.AiAgentToolVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiEntryAgentVo;
import org.dromara.ai.domain.vo.AiExpertAgentVo;
import org.dromara.ai.domain.vo.AiExpertVo;
import org.dromara.ai.mapper.AiAgentKbMapper;
import org.dromara.ai.mapper.AiAgentToolMapper;
import org.dromara.ai.mapper.AiAgentToolTrustMapper;
import org.dromara.ai.mapper.AiEntryAgentMapper;
import org.dromara.ai.mapper.AiExpertAgentMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 智能体定义 Service 业务层处理（REQ-AI-33：入口 / 专家物理拆表）
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiAgentServiceImpl implements IAiAgentService {

    /**
     * 默认系统提示词：定义页留空时落库使用
     */
    public static final String DEFAULT_SYS_PROMPT = """
        你是企业内部知识助手，负责基于企业自有资料回答同事的业务问题。
        回答要求：
        1. 当问题涉及企业内部的制度、流程、产品或技术资料时，优先调用 search_knowledge_base 工具检索资料，再依据检索结果作答；
        2. 答案必须基于检索到的资料，不得凭经验编造；
        3. 检索不到相关内容时，明确说明「未在企业知识库中找到相关依据」，并提示需要补充哪些资料；
        4. 使用简体中文回答，条理清晰，必要时用列表或步骤表达；
        5. 输出 Markdown 时严格遵守以下格式，否则会显示成乱码：标题（#）必须独占一行、标记与标题文字之间留一个空格，标题行内不得紧跟正文；列表项必须独占一行，以「- 」或「1. 」开头；表格前后各留一个空行。
        """;

    /**
     * 对话模型类型
     */
    private static final String TYPE_CHAT = "chat";

    /**
     * 状态：正常
     */
    private static final String STATUS_NORMAL = "0";

    /**
     * 可见范围：全员可见
     */
    private static final String SCOPE_ALL = "0";

    /**
     * 可见范围：本部门可见
     */
    private static final String SCOPE_DEPT = "1";

    /**
     * 可见范围：仅创建人可见
     */
    private static final String SCOPE_SELF = "2";

    private final AiEntryAgentMapper entryMapper;

    private final AiExpertAgentMapper expertMapper;

    private final AiAgentToolMapper agentToolMapper;

    private final AiAgentToolTrustMapper agentToolTrustMapper;

    private final AiAgentKbMapper agentKbMapper;

    private final AiModelConfigMapper modelConfigMapper;

    private final AiSessionMapper sessionMapper;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final IAiToolService toolService;

    private final IAiSkillService skillService;

    private final IAiSubagentService subagentService;

    private final AgentRegistry agentRegistry;

    private final UserService userService;

    private final AiProperties aiProperties;

    // ---------------------------------------------------------------- 入口智能体（小Z）

    @Override
    public AiEntryAgentVo queryEntryAgent() {
        AiEntryAgent entry = selectEntry();
        if (entry == null) {
            return null;
        }
        AiEntryAgentVo vo = MapstructUtils.convert(entry, AiEntryAgentVo.class);
        vo.setRole(AiAgent.ROLE_ENTRY);
        fillModelName(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateEntryAgent(AiEntryAgentBo bo) {
        AiEntryAgent exists = selectEntry();
        if (exists == null) {
            throw new ServiceException("入口智能体未配置");
        }
        AiEntryAgent update = MapstructUtils.convert(bo, AiEntryAgent.class);
        update.setId(exists.getId());
        update.setAgentCode(normalizeCode(update.getAgentCode()));
        update.setSysPrompt(resolveSysPrompt(update.getSysPrompt()));
        resolveChatModel(update.getModelId());
        validateRoleModels(update);
        applyDefaults(update, exists);
        validEntityBeforeSave(update.getAgentCode(), update.getId());
        boolean flag = entryMapper.updateById(update) > 0;
        if (flag) {
            agentRegistry.evict(update.getId());
        }
        return flag;
    }

    // ---------------------------------------------------------------- 专家智能体

    @Override
    public AiAgentVo queryById(Long id) {
        AiAgent entity = resolve(id);
        if (entity == null) {
            return null;
        }
        AiAgentVo vo;
        if (entity.isEntry()) {
            vo = MapstructUtils.convert((AiEntryAgent) entity, AiEntryAgentVo.class);
        } else {
            vo = MapstructUtils.convert((AiExpertAgent) entity, AiExpertAgentVo.class);
        }
        vo.setRole(entity.role());
        fillModelName(List.of(vo));
        fillBindings(List.of(vo));
        return vo;
    }

    @Override
    public PageResult<AiExpertAgentVo> queryPageList(AiExpertAgentBo bo, PageQuery pageQuery) {
        Page<AiExpertAgentVo> result = expertMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        fillModelName(result.getRecords());
        fillBindings(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiExpertAgentBo bo) {
        AiExpertAgent add = MapstructUtils.convert(bo, AiExpertAgent.class);
        add.setAgentCode(normalizeCode(add.getAgentCode()));
        add.setSysPrompt(resolveSysPrompt(add.getSysPrompt()));
        resolveChatModel(add.getModelId());
        validateRoleModels(add);
        applyDefaults(add, null);
        applyExpertDefaults(add);
        validEntityBeforeSave(add.getAgentCode(), add.getId());
        List<AiAgentToolBo> toolBindings = normalizeToolBindings(bo.getToolBindings());
        List<Long> kbIds = normalizeKbIds(bo.getKbIds());
        List<Long> skillIds = normalizeSkillIds(bo.getSkillIds());
        List<Long> subagentIds = normalizeSubagentIds(bo.getSubagentIds());
        boolean flag = expertMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            saveBindings(add.getId(), toolBindings, kbIds, skillIds, subagentIds);
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiExpertAgentBo bo) {
        AiExpertAgent exists = expertMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("专家不存在或已被删除");
        }
        AiExpertAgent update = MapstructUtils.convert(bo, AiExpertAgent.class);
        update.setAgentCode(normalizeCode(update.getAgentCode()));
        update.setSysPrompt(resolveSysPrompt(update.getSysPrompt()));
        resolveChatModel(update.getModelId());
        validateRoleModels(update);
        applyDefaults(update, exists);
        applyExpertDefaults(update);
        validEntityBeforeSave(update.getAgentCode(), update.getId());
        List<AiAgentToolBo> toolBindings = normalizeToolBindings(bo.getToolBindings());
        List<Long> kbIds = normalizeKbIds(bo.getKbIds());
        List<Long> skillIds = normalizeSkillIds(bo.getSkillIds());
        List<Long> subagentIds = normalizeSubagentIds(bo.getSubagentIds());
        boolean flag = expertMapper.updateById(update) > 0;
        if (flag) {
            saveBindings(update.getId(), toolBindings, kbIds, skillIds, subagentIds);
            agentRegistry.evict(update.getId());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            List<AiExpertAgent> list = expertMapper.selectByIds(ids);
            if (list.size() != ids.size()) {
                throw new ServiceException("您没有删除权限!");
            }
            LambdaQueryWrapper<AiSession> lqw = Wrappers.lambdaQuery();
            lqw.in(AiSession::getAgentId, ids);
            if (sessionMapper.selectCount(lqw) > 0) {
                throw new ServiceException("该专家下仍存在会话，请先删除相关会话后再删除专家");
            }
        }
        boolean flag = expertMapper.deleteByIds(ids) > 0;
        if (flag) {
            for (Long id : ids) {
                deleteBindings(id);
                agentRegistry.evict(id);
            }
        }
        return flag;
    }

    // ---------------------------------------------------------------- 选项与候选

    @Override
    public List<AiAgentVo> queryOptions() {
        Long userId = LoginHelper.getUserId();
        Long deptId = LoginHelper.getDeptId();
        List<AiAgentVo> result = new ArrayList<>();
        AiEntryAgent entry = selectEntry();
        if (entry != null && STATUS_NORMAL.equals(entry.getStatus())) {
            AiEntryAgentVo vo = MapstructUtils.convert(entry, AiEntryAgentVo.class);
            vo.setRole(AiAgent.ROLE_ENTRY);
            result.add(vo);
        }
        for (AiExpertAgent expert : expertMapper.selectList(visibleWrapper(userId, deptId))) {
            AiExpertAgentVo vo = MapstructUtils.convert(expert, AiExpertAgentVo.class);
            vo.setRole(AiAgent.ROLE_EXPERT);
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<AiExpertVo> queryExperts() {
        LambdaQueryWrapper<AiExpertAgent> lqw = visibleWrapper(LoginHelper.getUserId(), LoginHelper.getDeptId());
        lqw.eq(AiExpertAgent::getAutoRoute, AiExpertAgent.AUTO_ROUTE_ON);
        return expertMapper.selectList(lqw).stream().map(this::toExpertVo).toList();
    }

    @Override
    public List<AiExpertVo> queryVisibleExperts() {
        LambdaQueryWrapper<AiExpertAgent> lqw = visibleWrapper(LoginHelper.getUserId(), LoginHelper.getDeptId());
        return expertMapper.selectList(lqw).stream().map(this::toExpertVo).toList();
    }

    // ---------------------------------------------------------------- 装配与解析

    @Override
    public AiAgent getEnabledAgent(Long id) {
        AiAgent agent = resolve(id);
        if (agent == null) {
            throw new ServiceException("智能体不存在或已被删除");
        }
        if (!STATUS_NORMAL.equals(agent.getStatus())) {
            throw new ServiceException("智能体已停用：" + agent.getAgentName());
        }
        return agent;
    }

    @Override
    public AiAgent queryEntity(Long id) {
        return resolve(id);
    }

    @Override
    public List<AiAgent> queryEntities(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AiAgent> result = new ArrayList<>(ids.size());
        result.addAll(entryMapper.selectByIds(ids));
        result.addAll(expertMapper.selectByIds(ids));
        return result;
    }

    @Override
    public List<AiAgentVo> queryEntityVos(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AiAgentVo> result = new ArrayList<>(ids.size());
        for (AiEntryAgent entry : entryMapper.selectByIds(ids)) {
            AiEntryAgentVo vo = MapstructUtils.convert(entry, AiEntryAgentVo.class);
            vo.setRole(AiAgent.ROLE_ENTRY);
            result.add(vo);
        }
        for (AiExpertAgent expert : expertMapper.selectByIds(ids)) {
            AiExpertAgentVo vo = MapstructUtils.convert(expert, AiExpertAgentVo.class);
            vo.setRole(AiAgent.ROLE_EXPERT);
            result.add(vo);
        }
        return result;
    }

    // ---------------------------------------------------------------- 工具信任决策

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rememberToolForAgent(Long agentId, Long userId, String toolCode) {
        if (agentId == null || userId == null || StringUtils.isBlank(toolCode)) {
            return false;
        }
        String code = StringUtils.trim(toolCode);
        LambdaQueryWrapper<AiAgentToolTrust> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentToolTrust::getAgentId, agentId);
        lqw.eq(AiAgentToolTrust::getUserId, userId);
        lqw.eq(AiAgentToolTrust::getToolCode, code);
        AiAgentToolTrust exists = agentToolTrustMapper.selectOne(lqw);
        if (exists == null) {
            AiAgentToolTrust entity = new AiAgentToolTrust();
            entity.setAgentId(agentId);
            entity.setUserId(userId);
            entity.setToolCode(code);
            entity.setDecision(AiAgentToolTrust.DECISION_ALLOW);
            entity.setSource(AiAgentToolTrust.SOURCE_REMEMBER);
            entity.setRemark("对话确认卡片选择「记住该工具」自动写入");
            agentToolTrustMapper.insert(entity);
        } else if (!AiAgentToolTrust.DECISION_ALLOW.equals(exists.getDecision())) {
            AiAgentToolTrust update = new AiAgentToolTrust();
            update.setDecision(AiAgentToolTrust.DECISION_ALLOW);
            update.setSource(AiAgentToolTrust.SOURCE_REMEMBER);
            agentToolTrustMapper.update(update, lqw);
        }
        // 信任不参与装配（装配实例是智能体级、跨用户共享），故无需清理装配缓存：
        // 生效发生在对话链路每轮按提问用户同步会话级权限上下文时（见 AiTrustApplier#apply）
        return true;
    }

    @Override
    public List<AiAgentToolTrustVo> queryTrustedTools(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiAgentToolTrust> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentToolTrust::getAgentId, agentId);
        lqw.orderByAsc(AiAgentToolTrust::getToolCode);
        lqw.orderByAsc(AiAgentToolTrust::getUserId);
        List<AiAgentToolTrust> list = agentToolTrustMapper.selectList(lqw);
        Set<Long> userIds = new HashSet<>();
        for (AiAgentToolTrust entity : list) {
            if (entity.getUserId() != null) {
                userIds.add(entity.getUserId());
            }
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        List<AiAgentToolTrustVo> rows = new ArrayList<>(list.size());
        for (AiAgentToolTrust entity : list) {
            AiAgentToolTrustVo vo = new AiAgentToolTrustVo();
            vo.setToolCode(entity.getToolCode());
            vo.setUserId(entity.getUserId());
            vo.setUserName(userNames.get(entity.getUserId()));
            vo.setDecision(entity.getDecision());
            vo.setSource(entity.getSource());
            vo.setCreateTime(entity.getCreateTime());
            rows.add(vo);
        }
        return rows;
    }

    @Override
    public Set<String> trustedToolCodes(Long agentId, Long userId) {
        if (agentId == null || userId == null) {
            return Set.of();
        }
        LambdaQueryWrapper<AiAgentToolTrust> lqw = Wrappers.lambdaQuery();
        lqw.select(AiAgentToolTrust::getToolCode);
        lqw.eq(AiAgentToolTrust::getAgentId, agentId);
        lqw.eq(AiAgentToolTrust::getUserId, userId);
        lqw.eq(AiAgentToolTrust::getDecision, AiAgentToolTrust.DECISION_ALLOW);
        Set<String> codes = new LinkedHashSet<>();
        for (AiAgentToolTrust entity : agentToolTrustMapper.selectList(lqw)) {
            if (StringUtils.isNotBlank(entity.getToolCode())) {
                codes.add(entity.getToolCode());
            }
        }
        return codes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean revokeTrustedTool(Long agentId, Long userId, String toolCode) {
        if (agentId == null || userId == null || StringUtils.isBlank(toolCode)) {
            return false;
        }
        LambdaQueryWrapper<AiAgentToolTrust> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentToolTrust::getAgentId, agentId);
        lqw.eq(AiAgentToolTrust::getUserId, userId);
        lqw.eq(AiAgentToolTrust::getToolCode, StringUtils.trim(toolCode));
        // 删除后由对话链路每轮的用户级同步把该会话的 allow 规则摘除，故此处无需清理装配缓存
        return agentToolTrustMapper.delete(lqw) > 0;
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 取入口智能体（全平台唯一 1 行；库层由 {@code uk_ai_entry_agent_singleton} 强制）。
     *
     * @return 入口智能体；未配置时返回 null
     */
    private AiEntryAgent selectEntry() {
        return entryMapper.selectList(Wrappers.lambdaQuery()).stream().findFirst().orElse(null);
    }

    /**
     * 两表解析智能体实体（先入口表、再专家表）。
     *
     * @param id 智能体主键
     * @return 智能体实体；不存在时返回 null
     */
    private AiAgent resolve(Long id) {
        if (id == null) {
            return null;
        }
        AiEntryAgent entry = entryMapper.selectById(id);
        return entry != null ? entry : expertMapper.selectById(id);
    }

    /**
     * 「启用 ∩ 可见」专家查询条件（按主键升序）。
     *
     * @param userId 提问用户主键
     * @param deptId 提问用户部门主键
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiExpertAgent> visibleWrapper(Long userId, Long deptId) {
        LambdaQueryWrapper<AiExpertAgent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiExpertAgent::getStatus, STATUS_NORMAL);
        lqw.and(w -> w.eq(AiExpertAgent::getVisibleScope, SCOPE_ALL)
            .or(o -> o.eq(AiExpertAgent::getVisibleScope, SCOPE_DEPT).eq(AiExpertAgent::getOwnerDeptId, deptId))
            .or(o -> o.eq(AiExpertAgent::getVisibleScope, SCOPE_SELF).eq(AiExpertAgent::getCreateBy, userId)));
        lqw.orderByAsc(AiExpertAgent::getId);
        return lqw;
    }

    /**
     * 专家实体转路由候选视图（能力描述为空回落 {@code description}）。
     *
     * @param agent 专家实体
     * @return 候选专家视图对象
     */
    private AiExpertVo toExpertVo(AiExpertAgent agent) {
        AiExpertVo vo = new AiExpertVo();
        vo.setAgentId(agent.getId());
        vo.setAgentKey(agent.getAgentCode());
        vo.setAgentName(agent.getAgentName());
        vo.setExpertDomain(agent.getExpertDomain());
        vo.setCapabilityDesc(StringUtils.isBlank(agent.getCapabilityDesc())
            ? agent.getDescription() : agent.getCapabilityDesc());
        vo.setOutputContract(agent.getOutputContract());
        vo.setAutoRoute(agent.getAutoRoute());
        return vo;
    }

    /**
     * 补充对话模型名称。
     *
     * @param list 智能体列表
     */
    private void fillModelName(List<? extends AiAgentVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> modelIds = new HashSet<>();
        for (AiAgentVo vo : list) {
            if (vo.getModelId() != null) {
                modelIds.add(vo.getModelId());
            }
        }
        if (modelIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = new HashMap<>();
        for (AiModelConfig model : modelConfigMapper.selectByIds(modelIds)) {
            nameMap.put(model.getId(), model.getConfigName());
        }
        list.forEach(vo -> vo.setModelName(nameMap.get(vo.getModelId())));
    }

    /**
     * 批量补充工具集与知识库绑定。
     *
     * @param list 智能体列表
     */
    private void fillBindings(List<? extends AiAgentVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> agentIds = list.stream().map(AiAgentVo::getId).filter(java.util.Objects::nonNull).toList();
        if (agentIds.isEmpty()) {
            return;
        }
        Map<Long, List<AiAgentToolVo>> toolMap = new HashMap<>();
        LambdaQueryWrapper<AiAgentTool> toolLqw = Wrappers.lambdaQuery();
        toolLqw.in(AiAgentTool::getAgentId, agentIds);
        toolLqw.orderByAsc(AiAgentTool::getId);
        for (AiAgentTool entity : agentToolMapper.selectList(toolLqw)) {
            AiAgentToolVo item = new AiAgentToolVo();
            item.setToolCode(entity.getToolCode());
            item.setDecision(entity.getDecision());
            toolMap.computeIfAbsent(entity.getAgentId(), k -> new ArrayList<>()).add(item);
        }
        Map<Long, List<Long>> kbMap = new HashMap<>();
        LambdaQueryWrapper<AiAgentKb> kbLqw = Wrappers.lambdaQuery();
        kbLqw.in(AiAgentKb::getAgentId, agentIds);
        kbLqw.orderByAsc(AiAgentKb::getId);
        for (AiAgentKb entity : agentKbMapper.selectList(kbLqw)) {
            kbMap.computeIfAbsent(entity.getAgentId(), k -> new ArrayList<>()).add(entity.getKbId());
        }
        Map<Long, List<Long>> skillMap = skillService.queryAgentSkillMap(agentIds);
        Map<Long, List<Long>> subagentMap = subagentService.queryAgentSubagentMap(agentIds);
        for (AiAgentVo vo : list) {
            vo.setToolBindings(toolMap.getOrDefault(vo.getId(), List.of()));
            vo.setKbIds(kbMap.getOrDefault(vo.getId(), List.of()));
            vo.setSkillIds(skillMap.getOrDefault(vo.getId(), List.of()));
            vo.setSubagentIds(subagentMap.getOrDefault(vo.getId(), List.of()));
        }
    }

    /**
     * 校验智能体编码格式。
     *
     * @param agentCode 原始编码
     * @return 规范化后的编码
     */
    private String normalizeCode(String agentCode) {
        String code = StringUtils.trim(agentCode).toLowerCase();
        if (!code.matches("[a-z][a-z0-9_]{1,60}")) {
            throw new ServiceException("智能体编码只能包含小写字母、数字与下划线，且以字母开头（2~61 位）");
        }
        return code;
    }

    /**
     * 补齐默认提示词。
     *
     * @param sysPrompt 原始提示词
     * @return 生效的提示词
     */
    private String resolveSysPrompt(String sysPrompt) {
        return StringUtils.isBlank(sysPrompt) ? DEFAULT_SYS_PROMPT : sysPrompt;
    }

    /**
     * 校验对话模型可用性。
     *
     * @param modelId 对话模型主键
     * @return 对话模型实体
     */
    private AiModelConfig resolveChatModel(Long modelId) {
        if (modelId == null) {
            throw new ServiceException("绑定对话模型不能为空");
        }
        AiModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException("所选模型不存在或已被删除");
        }
        if (!TYPE_CHAT.equalsIgnoreCase(StringUtils.trim(model.getModelType()))) {
            throw new ServiceException("所选模型不是对话模型：" + model.getConfigName());
        }
        if (!STATUS_NORMAL.equals(model.getStatus())) {
            throw new ServiceException("所选对话模型已停用：" + model.getConfigName());
        }
        return model;
    }

    /**
     * 校验可空的角色模型槽位（记忆 / 压缩摘要 / 子智能体）。
     *
     * <p>三个槽位均已在装配层接入：压缩摘要模型用于上下文压缩、记忆模型用于记忆抽取与合并、
     * 子智能体模型用于子智能体未单独指定模型时的默认值（REQ-AI-04），
     * 未配置或不可用时统一回退主模型。</p>
     *
     * @param entity 智能体实体
     */
    private void validateRoleModels(AiAgent entity) {
        entity.setMemoryModelId(resolveRoleModel(entity.getMemoryModelId(), "记忆抽取模型"));
        entity.setSummaryModelId(resolveRoleModel(entity.getSummaryModelId(), "上下文压缩摘要模型"));
        entity.setSubagentModelId(resolveRoleModel(entity.getSubagentModelId(), "子智能体默认模型"));
    }

    /**
     * 校验单个角色模型槽位。
     *
     * @param modelId 模型主键，可空
     * @param label   槽位名称（用于报错）
     * @return 校验通过的模型主键
     */
    private Long resolveRoleModel(Long modelId, String label) {
        if (modelId == null) {
            return null;
        }
        AiModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException(label + "不存在或已被删除");
        }
        if (!TYPE_CHAT.equalsIgnoreCase(StringUtils.trim(model.getModelType()))) {
            throw new ServiceException(label + "必须是对话模型：" + model.getConfigName());
        }
        if (!STATUS_NORMAL.equals(model.getStatus())) {
            throw new ServiceException(label + "已停用：" + model.getConfigName());
        }
        return modelId;
    }

    /**
     * 校验并规范化工具绑定集合（含授权决策）。
     *
     * @param bindings 原始工具绑定集合
     * @return 去重后的工具绑定集合
     */
    private List<AiAgentToolBo> normalizeToolBindings(List<AiAgentToolBo> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<String, AiAgentToolBo> result = new LinkedHashMap<>();
        for (AiAgentToolBo item : bindings) {
            if (item == null || StringUtils.isBlank(item.getToolCode())) {
                continue;
            }
            String code = StringUtils.trim(item.getToolCode());
            if (result.containsKey(code)) {
                continue;
            }
            if (!toolService.exists(code)) {
                throw new ServiceException("业务工具不存在：" + code);
            }
            AiAgentToolBo normalized = new AiAgentToolBo();
            normalized.setToolCode(code);
            normalized.setDecision(normalizeDecision(item.getDecision()));
            result.put(code, normalized);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 规范化授权决策，非法值按「允许」处理。
     *
     * @param decision 原始决策
     * @return 生效决策
     */
    private String normalizeDecision(String decision) {
        String value = StringUtils.trim(decision);
        if (AiAgentTool.DECISION_ASK.equals(value) || AiAgentTool.DECISION_DENY.equals(value)) {
            return value;
        }
        return AiAgentTool.DECISION_ALLOW;
    }

    /**
     * 校验并规范化知识库绑定集合。
     *
     * @param kbIds 原始知识库主键集合
     * @return 去重后的知识库主键集合
     */
    private List<Long> normalizeKbIds(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(new LinkedHashSet<>(kbIds));
        for (Long kbId : result) {
            knowledgeBaseService.getEnabledKnowledgeBase(kbId);
        }
        // 生效范围（REQ-AI-17）：只能绑定对**保存人**可见的知识库，避免配了用不上、或代人越权绑定
        knowledgeBaseService.validateVisible(result, LoginHelper.getUserId(), "绑定");
        return result;
    }

    /**
     * 校验并规范化技能绑定集合。
     *
     * @param skillIds 原始技能主键集合
     * @return 去重后的技能主键集合
     */
    private List<Long> normalizeSkillIds(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(new LinkedHashSet<>(skillIds));
        for (Long skillId : result) {
            skillService.getEnabledSkill(skillId);
        }
        // 生效范围（REQ-AI-10）：只能绑定对**保存人**可见的技能
        skillService.validateVisible(result, LoginHelper.getUserId(), "绑定");
        return result;
    }

    /**
     * 校验并规范化子智能体绑定集合（REQ-AI-04）。
     *
     * <p>只校验「存在且启用」：子智能体是平台级定义，不设生效范围；
     * 其工具白名单的只读约束在子智能体保存时已强制校验。</p>
     *
     * @param subagentIds 原始子智能体主键集合
     * @return 去重后的子智能体主键集合
     */
    private List<Long> normalizeSubagentIds(List<Long> subagentIds) {
        if (subagentIds == null || subagentIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(new LinkedHashSet<>(subagentIds));
        for (Long subagentId : result) {
            subagentService.getEnabledSubagent(subagentId);
        }
        return result;
    }

    /**
     * 覆盖式保存智能体的工具（含授权决策）、知识库、技能与子智能体绑定。
     *
     * @param agentId     智能体主键
     * @param bindings    工具绑定集合
     * @param kbIds       知识库主键集合
     * @param skillIds    技能主键集合
     * @param subagentIds 子智能体主键集合
     */
    private void saveBindings(Long agentId, List<AiAgentToolBo> bindings, List<Long> kbIds,
                              List<Long> skillIds, List<Long> subagentIds) {
        LambdaQueryWrapper<AiAgentTool> toolLqw = Wrappers.lambdaQuery();
        toolLqw.eq(AiAgentTool::getAgentId, agentId);
        agentToolMapper.delete(toolLqw);
        for (AiAgentToolBo item : bindings) {
            AiAgentTool entity = new AiAgentTool();
            entity.setAgentId(agentId);
            entity.setToolCode(item.getToolCode());
            entity.setDecision(item.getDecision());
            agentToolMapper.insert(entity);
        }
        LambdaQueryWrapper<AiAgentKb> kbLqw = Wrappers.lambdaQuery();
        kbLqw.eq(AiAgentKb::getAgentId, agentId);
        agentKbMapper.delete(kbLqw);
        for (Long kbId : kbIds) {
            AiAgentKb entity = new AiAgentKb();
            entity.setAgentId(agentId);
            entity.setKbId(kbId);
            agentKbMapper.insert(entity);
        }
        skillService.replaceAgentBindings(agentId, skillIds);
        subagentService.replaceAgentBindings(agentId, subagentIds);
    }

    /**
     * 删除智能体的工具、知识库、技能与子智能体绑定。
     *
     * @param agentId 智能体主键
     */
    private void deleteBindings(Long agentId) {
        LambdaQueryWrapper<AiAgentTool> toolLqw = Wrappers.lambdaQuery();
        toolLqw.eq(AiAgentTool::getAgentId, agentId);
        agentToolMapper.delete(toolLqw);
        // 「记住该工具」的信任决策一并清理，避免残留的免确认决策在智能体重建后误生效
        LambdaQueryWrapper<AiAgentToolTrust> trustLqw = Wrappers.lambdaQuery();
        trustLqw.eq(AiAgentToolTrust::getAgentId, agentId);
        agentToolTrustMapper.delete(trustLqw);
        LambdaQueryWrapper<AiAgentKb> kbLqw = Wrappers.lambdaQuery();
        kbLqw.eq(AiAgentKb::getAgentId, agentId);
        agentKbMapper.delete(kbLqw);
        skillService.deleteAgentBindings(List.of(agentId));
        subagentService.deleteAgentBindings(List.of(agentId));
    }

    /**
     * 填充公共默认值：新增时取系统默认，修改时保留原值。
     *
     * @param entity 实体
     * @param exists 原实体（新增时为 null）
     */
    private void applyDefaults(AiAgent entity, AiAgent exists) {
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(exists == null ? STATUS_NORMAL : exists.getStatus());
        }
        if (StringUtils.isBlank(entity.getVisibleScope())) {
            entity.setVisibleScope(exists == null ? SCOPE_ALL : exists.getVisibleScope());
        }
        if (entity.getMaxIters() == null) {
            entity.setMaxIters(exists == null ? aiProperties.getChat().getDefaultMaxIters() : exists.getMaxIters());
        }
        if (entity.getOwnerDeptId() == null && exists != null) {
            entity.setOwnerDeptId(exists.getOwnerDeptId());
        }
    }

    /**
     * 填充专家专属默认值（REQ-AI-33）。
     *
     * <p>写工具开关已从配置页移除，恒为「允许」（写操作仍受三档 + HITL 约束）；
     * 自动路由开关留空按「开启」处理。</p>
     *
     * @param entity 专家实体
     */
    private void applyExpertDefaults(AiExpertAgent entity) {
        entity.setExpertWriteEnabled(AiAgent.WRITE_ENABLED);
        entity.setAutoRoute(AiExpertAgent.AUTO_ROUTE_OFF.equals(entity.getAutoRoute())
            ? AiExpertAgent.AUTO_ROUTE_OFF : AiExpertAgent.AUTO_ROUTE_ON);
    }

    /**
     * 构建专家动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiExpertAgent> buildQueryWrapper(AiExpertAgentBo bo) {
        LambdaQueryWrapper<AiExpertAgent> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getNameKeyword()), AiExpertAgent::getAgentName, bo.getNameKeyword());
        lqw.like(StringUtils.isNotBlank(bo.getAgentCode()), AiExpertAgent::getAgentCode, bo.getAgentCode());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiExpertAgent::getStatus, bo.getStatus());
        lqw.orderByDesc(AiExpertAgent::getCreateTime);
        return lqw;
    }

    /**
     * 保存前的数据校验：{@code agent_code} 在**两表之间**唯一（库层无法跨表建唯一索引，见修复 B6）。
     *
     * <p>{@code agent_code} 同时是 AgentScope 的 agent name 与路由 key，跨表重名会导致装配冲突。</p>
     *
     * @param agentCode 智能体编码（已规范化）
     * @param excludeId 排除的主键（修改时传自身主键，可空）
     */
    private void validEntityBeforeSave(String agentCode, Long excludeId) {
        LambdaQueryWrapper<AiEntryAgent> entryLqw = Wrappers.lambdaQuery();
        entryLqw.eq(AiEntryAgent::getAgentCode, agentCode);
        entryLqw.ne(excludeId != null, AiEntryAgent::getId, excludeId);
        if (entryMapper.selectCount(entryLqw) > 0) {
            throw new ServiceException("智能体编码已存在：" + agentCode);
        }
        LambdaQueryWrapper<AiExpertAgent> expertLqw = Wrappers.lambdaQuery();
        expertLqw.eq(AiExpertAgent::getAgentCode, agentCode);
        expertLqw.ne(excludeId != null, AiExpertAgent::getId, excludeId);
        if (expertMapper.selectCount(expertLqw) > 0) {
            throw new ServiceException("智能体编码已存在：" + agentCode);
        }
    }

}
