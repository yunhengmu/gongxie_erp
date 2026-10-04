package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.AiScenarioPackSkill;
import org.dromara.ai.domain.AiScenarioPackTool;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiScenarioPackBo;
import org.dromara.ai.domain.bo.AiScenarioPackMcpToolBo;
import org.dromara.ai.domain.bo.AiScenarioPackToolBo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackMcpToolVo;
import org.dromara.ai.domain.vo.AiScenarioPackOptionVo;
import org.dromara.ai.domain.vo.AiScenarioPackToolVo;
import org.dromara.ai.domain.vo.AiScenarioPackVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiScenarioPackMcpToolMapper;
import org.dromara.ai.mapper.AiScenarioPackSkillMapper;
import org.dromara.ai.mapper.AiScenarioPackToolMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
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
import java.util.Objects;
import java.util.Set;

/**
 * AI 场景包 Service 业务层处理
 *
 * <p>本文档口径（见 {@code script/sql/postgres/postgres_ry_ai.sql} 第三十二部分）：</p>
 * <ul>
 *   <li>三类绑定集合「先删后插」全量覆盖（关联表无 del_flag）；本体为逻辑删除。</li>
 *   <li>包内业务工具必须是已登记的业务工具；MCP 工具必须是「启用中的连接 + 其 enable_tools 白名单子集」。</li>
 *   <li>生效范围与 {@code ai_agent} / {@code ai_knowledge_base} 同口径：0 公开 / 1 本部门 / 2 仅本人。</li>
 * </ul>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiScenarioPackServiceImpl implements IAiScenarioPackService {

    private final AiScenarioPackMapper baseMapper;

    private final AiScenarioPackToolMapper packToolMapper;

    private final AiScenarioPackMcpToolMapper packMcpToolMapper;

    private final AiScenarioPackSkillMapper packSkillMapper;

    private final AiToolMapper toolMapper;

    private final AiSkillMapper skillMapper;

    private final AiMcpClientMapper mcpClientMapper;

    private final IAiToolService toolService;

    private final IAiSkillService skillService;

    private final UserService userService;

    private final DeptService deptService;

    @Override
    public PageResult<AiScenarioPackVo> queryPageList(AiScenarioPackBo bo, PageQuery pageQuery) {
        Page<AiScenarioPackVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiScenarioPackVo> records = result.getRecords();
        fillDisplayFields(records);
        return PageResult.build(records, result.getTotal());
    }

    @Override
    public AiScenarioPackVo queryById(Long id) {
        AiScenarioPackVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("场景包不存在或已被删除");
        }
        fillDisplayFields(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiScenarioPackBo bo) {
        AiScenarioPack add = MapstructUtils.convert(bo, AiScenarioPack.class);
        applyFields(add, bo);
        validCodeUnique(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            saveBindings(add.getId(), bo);
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiScenarioPackBo bo) {
        AiScenarioPack exists = bo.getId() == null ? null : baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("场景包不存在或已被删除");
        }
        AiScenarioPack update = MapstructUtils.convert(bo, AiScenarioPack.class);
        applyFields(update, bo);
        if (!exists.getPackCode().equals(update.getPackCode())) {
            throw new ServiceException("场景包编码不允许修改（已作为会话快照使用）");
        }
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            saveBindings(update.getId(), bo);
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的场景包");
        }
        List<AiScenarioPack> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("场景包不存在或已被删除");
        }
        boolean flag = baseMapper.deleteByIds(ids) > 0;
        if (flag) {
            for (Long id : ids) {
                deleteBindings(id);
            }
        }
        return flag;
    }

    @Override
    public List<AiScenarioPackOptionVo> queryOptions() {
        Long userId = LoginHelper.getUserId();
        Long deptId = LoginHelper.getDeptId();
        LambdaQueryWrapper<AiScenarioPack> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiScenarioPack::getStatus, AiScenarioPack.STATUS_ENABLED);
        lqw.and(w -> w.eq(AiScenarioPack::getVisibleScope, AiScenarioPack.SCOPE_ALL)
            .or(o -> o.eq(AiScenarioPack::getVisibleScope, AiScenarioPack.SCOPE_DEPT)
                .eq(AiScenarioPack::getOwnerDeptId, deptId))
            .or(o -> o.eq(AiScenarioPack::getVisibleScope, AiScenarioPack.SCOPE_SELF)
                .eq(AiScenarioPack::getCreateBy, userId)));
        lqw.orderByDesc(AiScenarioPack::getUpdateTime);
        List<AiScenarioPack> packs = baseMapper.selectList(lqw);
        if (packs.isEmpty()) {
            return List.of();
        }
        List<Long> packIds = packs.stream().map(AiScenarioPack::getId).toList();
        Map<Long, Integer> toolCounts = countToolsByPack(packIds);
        Map<Long, Integer> mcpCounts = countMcpToolsByPack(packIds);
        Map<Long, Integer> skillCounts = countSkillsByPack(packIds);
        List<AiScenarioPackOptionVo> options = new ArrayList<>(packs.size());
        for (AiScenarioPack pack : packs) {
            AiScenarioPackOptionVo option = new AiScenarioPackOptionVo();
            option.setId(pack.getId());
            option.setPackCode(pack.getPackCode());
            option.setPackName(pack.getPackName());
            option.setDescription(pack.getDescription());
            option.setToolCount(toolCounts.getOrDefault(pack.getId(), 0));
            option.setMcpToolCount(mcpCounts.getOrDefault(pack.getId(), 0));
            option.setSkillCount(skillCounts.getOrDefault(pack.getId(), 0));
            options.add(option);
        }
        return options;
    }

    @Override
    public List<AiScenarioPackMcpOptionVo> queryMcpToolOptions() {
        LambdaQueryWrapper<AiMcpClient> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMcpClient::getStatus, AiMcpClient.STATUS_ENABLED);
        lqw.orderByAsc(AiMcpClient::getServerName);
        List<AiScenarioPackMcpOptionVo> options = new ArrayList<>();
        for (AiMcpClient client : mcpClientMapper.selectList(lqw)) {
            AiScenarioPackMcpOptionVo option = new AiScenarioPackMcpOptionVo();
            option.setMcpClientId(client.getId());
            option.setServerName(client.getServerName());
            option.setTools(splitTools(client.getEnableTools()));
            options.add(option);
        }
        return options;
    }

    @Override
    public AiScenarioPack getEnabledPackOrNull(Long id) {
        if (id == null) {
            return null;
        }
        AiScenarioPack pack = baseMapper.selectById(id);
        if (pack == null || !AiScenarioPack.STATUS_ENABLED.equals(pack.getStatus())) {
            log.warn("会话所选场景包不存在或已停用，本次装配按「无包」处理：packId={}", id);
            return null;
        }
        return pack;
    }

    @Override
    public void validateSelectable(Long id) {
        if (id == null) {
            return;
        }
        AiScenarioPack pack = baseMapper.selectById(id);
        if (pack == null) {
            throw new ServiceException("场景包不存在或已被删除");
        }
        if (!AiScenarioPack.STATUS_ENABLED.equals(pack.getStatus())) {
            throw new ServiceException("场景包已停用：" + pack.getPackName());
        }
        if (!visibleTo(pack, LoginHelper.getUserId(), LoginHelper.getDeptId())) {
            throw new ServiceException("场景包不在你的生效范围内，不能选用：" + pack.getPackName());
        }
    }

    @Override
    public List<AiScenarioPackTool> queryPackTools(Long packId) {
        if (packId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiScenarioPackTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiScenarioPackTool::getPackId, packId);
        lqw.orderByAsc(AiScenarioPackTool::getId);
        return packToolMapper.selectList(lqw);
    }

    @Override
    public List<AiScenarioPackMcpTool> queryPackMcpTools(Long packId) {
        if (packId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiScenarioPackMcpTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiScenarioPackMcpTool::getPackId, packId);
        lqw.orderByAsc(AiScenarioPackMcpTool::getId);
        return packMcpToolMapper.selectList(lqw);
    }

    @Override
    public List<Long> queryPackSkillIds(Long packId) {
        if (packId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiScenarioPackSkill> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiScenarioPackSkill::getPackId, packId);
        lqw.orderByAsc(AiScenarioPackSkill::getId);
        return packSkillMapper.selectList(lqw).stream().map(AiScenarioPackSkill::getSkillId).toList();
    }

    /**
     * 规范化基础字段并校验生效范围取值。
     *
     * @param entity 待保存实体
     * @param bo     业务对象
     */
    private void applyFields(AiScenarioPack entity, AiScenarioPackBo bo) {
        entity.setPackCode(normalizeCode(bo.getPackCode()));
        entity.setPackName(StringUtils.trim(bo.getPackName()));
        entity.setDescription(StringUtils.trim(bo.getDescription()));
        String scope = StringUtils.trim(entity.getVisibleScope());
        if (StringUtils.isBlank(scope)) {
            scope = AiScenarioPack.SCOPE_ALL;
        }
        if (!AiScenarioPack.SCOPE_ALL.equals(scope) && !AiScenarioPack.SCOPE_DEPT.equals(scope)
            && !AiScenarioPack.SCOPE_SELF.equals(scope)) {
            throw new ServiceException("生效范围只能是 0 公开 / 1 本部门 / 2 仅本人");
        }
        entity.setVisibleScope(scope);
        // 「本部门」需落归属部门；其余档位不写（保持空，避免残留旧部门导致误判）
        if (AiScenarioPack.SCOPE_DEPT.equals(scope) && entity.getOwnerDeptId() == null) {
            entity.setOwnerDeptId(LoginHelper.getDeptId());
        } else if (!AiScenarioPack.SCOPE_DEPT.equals(scope)) {
            entity.setOwnerDeptId(null);
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(AiScenarioPack.STATUS_ENABLED);
        }
    }

    /**
     * 校验并规范化场景包编码。
     *
     * @param raw 原始编码
     * @return 规范化后的编码
     */
    private String normalizeCode(String raw) {
        String code = StringUtils.trim(raw).toLowerCase();
        if (!code.matches("^[a-z][a-z0-9_-]{1,63}$")) {
            throw new ServiceException("场景包编码只能包含小写字母、数字、下划线或短横线，且以字母开头（2~64 位）");
        }
        return code;
    }

    /**
     * 校验场景包编码唯一。
     *
     * @param entity 待保存实体
     */
    private void validCodeUnique(AiScenarioPack entity) {
        LambdaQueryWrapper<AiScenarioPack> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiScenarioPack::getPackCode, entity.getPackCode());
        lqw.ne(entity.getId() != null, AiScenarioPack::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("场景包编码已存在：" + entity.getPackCode());
        }
    }

    /**
     * 覆盖式保存三类绑定（先删后插）。
     *
     * @param packId 场景包主键
     * @param bo     业务对象
     */
    private void saveBindings(Long packId, AiScenarioPackBo bo) {
        deleteBindings(packId);
        for (AiScenarioPackToolBo item : normalizeToolBindings(bo.getToolBindings())) {
            AiScenarioPackTool entity = new AiScenarioPackTool();
            entity.setPackId(packId);
            entity.setToolCode(item.getToolCode());
            entity.setDecision(item.getDecision());
            packToolMapper.insert(entity);
        }
        for (AiScenarioPackMcpToolBo item : normalizeMcpBindings(bo.getMcpToolBindings())) {
            AiScenarioPackMcpTool entity = new AiScenarioPackMcpTool();
            entity.setPackId(packId);
            entity.setMcpClientId(item.getMcpClientId());
            entity.setToolName(item.getToolName());
            entity.setDecision(item.getDecision());
            packMcpToolMapper.insert(entity);
        }
        for (Long skillId : normalizeSkillIds(bo.getSkillIds())) {
            AiScenarioPackSkill entity = new AiScenarioPackSkill();
            entity.setPackId(packId);
            entity.setSkillId(skillId);
            packSkillMapper.insert(entity);
        }
    }

    /**
     * 删除场景包的三类绑定（物理删除）。
     *
     * @param packId 场景包主键
     */
    private void deleteBindings(Long packId) {
        packToolMapper.delete(Wrappers.<AiScenarioPackTool>lambdaQuery()
            .eq(AiScenarioPackTool::getPackId, packId));
        packMcpToolMapper.delete(Wrappers.<AiScenarioPackMcpTool>lambdaQuery()
            .eq(AiScenarioPackMcpTool::getPackId, packId));
        packSkillMapper.delete(Wrappers.<AiScenarioPackSkill>lambdaQuery()
            .eq(AiScenarioPackSkill::getPackId, packId));
    }

    /**
     * 规范化业务工具绑定：去重、校验工具已登记、决策三档收敛。
     *
     * @param bindings 原始绑定
     * @return 规范化后的绑定
     */
    private List<AiScenarioPackToolBo> normalizeToolBindings(List<AiScenarioPackToolBo> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<String, AiScenarioPackToolBo> result = new LinkedHashMap<>();
        for (AiScenarioPackToolBo item : bindings) {
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
            AiScenarioPackToolBo normalized = new AiScenarioPackToolBo();
            normalized.setToolCode(code);
            normalized.setDecision(normalizeDecision(item.getDecision()));
            result.put(code, normalized);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 规范化 MCP 工具绑定：去重、校验连接启用且工具在其白名单内、决策三档收敛。
     *
     * @param bindings 原始绑定
     * @return 规范化后的绑定
     */
    private List<AiScenarioPackMcpToolBo> normalizeMcpBindings(List<AiScenarioPackMcpToolBo> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, AiMcpClient> clientCache = new HashMap<>();
        Map<String, AiScenarioPackMcpToolBo> result = new LinkedHashMap<>();
        for (AiScenarioPackMcpToolBo item : bindings) {
            if (item == null || item.getMcpClientId() == null || StringUtils.isBlank(item.getToolName())) {
                continue;
            }
            String toolName = StringUtils.trim(item.getToolName());
            String key = item.getMcpClientId() + "#" + toolName;
            if (result.containsKey(key)) {
                continue;
            }
            AiMcpClient client = clientCache.computeIfAbsent(item.getMcpClientId(),
                mcpClientMapper::selectById);
            if (client == null || !AiMcpClient.STATUS_ENABLED.equals(client.getStatus())) {
                throw new ServiceException("外部 MCP 连接不存在或已停用：" + item.getMcpClientId());
            }
            List<String> whitelist = splitTools(client.getEnableTools());
            if (whitelist.isEmpty()) {
                throw new ServiceException("外部 MCP 连接未配置工具白名单，无法勾选工具："
                    + client.getServerName());
            }
            if (!whitelist.contains(toolName)) {
                throw new ServiceException("工具不在该 MCP 连接的白名单内：" + client.getServerName()
                    + " / " + toolName);
            }
            AiScenarioPackMcpToolBo normalized = new AiScenarioPackMcpToolBo();
            normalized.setMcpClientId(item.getMcpClientId());
            normalized.setToolName(toolName);
            normalized.setDecision(normalizeDecision(item.getDecision()));
            result.put(key, normalized);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 规范化技能绑定：去重并校验技能「启用中」。
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
        return result;
    }

    /**
     * 规范化授权决策，非法值按「需人工确认」处理（与库表默认值一致）。
     *
     * @param decision 原始决策
     * @return 生效决策
     */
    private String normalizeDecision(String decision) {
        String value = StringUtils.trim(decision);
        if (AiScenarioPackTool.DECISION_ALLOW.equals(value)
            || AiScenarioPackTool.DECISION_DENY.equals(value)) {
            return value;
        }
        return AiScenarioPackTool.DECISION_ASK;
    }

    /**
     * 判断场景包对指定用户是否可见。
     *
     * @param pack   场景包
     * @param userId 用户主键
     * @param deptId 部门主键
     * @return 是否可见
     */
    private boolean visibleTo(AiScenarioPack pack, Long userId, Long deptId) {
        String scope = StringUtils.isBlank(pack.getVisibleScope())
            ? AiScenarioPack.SCOPE_ALL : pack.getVisibleScope();
        return switch (scope) {
            case AiScenarioPack.SCOPE_DEPT -> Objects.equals(pack.getOwnerDeptId(), deptId);
            case AiScenarioPack.SCOPE_SELF -> Objects.equals(pack.getCreateBy(), userId);
            default -> true;
        };
    }

    /**
     * 构建场景包动态查询条件（管理页全量）。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiScenarioPack> buildQueryWrapper(AiScenarioPackBo bo) {
        LambdaQueryWrapper<AiScenarioPack> lqw = Wrappers.lambdaQuery();
        lqw.and(StringUtils.isNotBlank(bo.getKeyword()), w -> w
            .like(AiScenarioPack::getPackCode, bo.getKeyword())
            .or().like(AiScenarioPack::getPackName, bo.getKeyword())
            .or().like(AiScenarioPack::getDescription, bo.getKeyword()));
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiScenarioPack::getStatus, bo.getStatus());
        lqw.eq(StringUtils.isNotBlank(bo.getVisibleScope()),
            AiScenarioPack::getVisibleScope, bo.getVisibleScope());
        lqw.orderByDesc(AiScenarioPack::getUpdateTime);
        return lqw;
    }

    /**
     * 填充三类绑定集合、工具 / 技能 / Server 显示名、部门名与创建人昵称。
     *
     * @param list 场景包列表
     */
    private void fillDisplayFields(List<AiScenarioPackVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> deptIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AiScenarioPackVo vo : list) {
            if (vo.getOwnerDeptId() != null) {
                deptIds.add(vo.getOwnerDeptId());
            }
            if (vo.getCreateBy() != null) {
                userIds.add(vo.getCreateBy());
            }
        }
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptService.selectDeptNamesByIds(deptIds);
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        for (AiScenarioPackVo vo : list) {
            vo.setOwnerDeptName(vo.getOwnerDeptId() == null ? null : deptNames.get(vo.getOwnerDeptId()));
            vo.setCreateByName(vo.getCreateBy() == null ? null : userNames.get(vo.getCreateBy()));
            fillBindings(vo);
        }
    }

    /**
     * 填充单个场景包的三类绑定集合。
     *
     * @param vo 场景包视图对象
     */
    private void fillBindings(AiScenarioPackVo vo) {
        List<AiScenarioPackTool> tools = queryPackTools(vo.getId());
        Map<String, AiTool> toolByCode = new HashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        for (AiScenarioPackTool tool : tools) {
            codes.add(tool.getToolCode());
        }
        if (!codes.isEmpty()) {
            for (AiTool tool : toolMapper.selectList(Wrappers.<AiTool>lambdaQuery()
                .in(AiTool::getToolCode, codes))) {
                toolByCode.put(tool.getToolCode(), tool);
            }
        }
        List<AiScenarioPackToolVo> toolVos = new ArrayList<>(tools.size());
        for (AiScenarioPackTool tool : tools) {
            AiScenarioPackToolVo toolVo = new AiScenarioPackToolVo();
            toolVo.setToolCode(tool.getToolCode());
            toolVo.setDecision(tool.getDecision());
            AiTool definition = toolByCode.get(tool.getToolCode());
            toolVo.setToolName(definition == null ? tool.getToolCode() : definition.getToolName());
            toolVo.setToolType(definition == null ? null : definition.getToolType());
            toolVos.add(toolVo);
        }
        vo.setToolBindings(toolVos);

        List<AiScenarioPackMcpTool> mcpTools = queryPackMcpTools(vo.getId());
        Map<Long, String> serverNames = new HashMap<>();
        Set<Long> clientIds = new LinkedHashSet<>();
        for (AiScenarioPackMcpTool mcpTool : mcpTools) {
            clientIds.add(mcpTool.getMcpClientId());
        }
        if (!clientIds.isEmpty()) {
            for (AiMcpClient client : mcpClientMapper.selectByIds(clientIds)) {
                serverNames.put(client.getId(), client.getServerName());
            }
        }
        List<AiScenarioPackMcpToolVo> mcpVos = new ArrayList<>(mcpTools.size());
        for (AiScenarioPackMcpTool mcpTool : mcpTools) {
            AiScenarioPackMcpToolVo mcpVo = new AiScenarioPackMcpToolVo();
            mcpVo.setMcpClientId(mcpTool.getMcpClientId());
            mcpVo.setServerName(serverNames.get(mcpTool.getMcpClientId()));
            mcpVo.setToolName(mcpTool.getToolName());
            mcpVo.setDecision(mcpTool.getDecision());
            mcpVos.add(mcpVo);
        }
        vo.setMcpToolBindings(mcpVos);

        List<Long> skillIds = queryPackSkillIds(vo.getId());
        vo.setSkillIds(skillIds);
        if (skillIds.isEmpty()) {
            vo.setSkillNames(List.of());
        } else {
            Map<Long, String> names = new HashMap<>();
            for (AiSkill skill : skillMapper.selectByIds(skillIds)) {
                names.put(skill.getId(), skill.getName());
            }
            vo.setSkillNames(skillIds.stream().map(id -> names.getOrDefault(id, "#" + id)).toList());
        }
    }

    /**
     * 统计各场景包的业务工具数量。
     *
     * @param packIds 场景包主键集合
     * @return 场景包主键 → 数量
     */
    private Map<Long, Integer> countToolsByPack(List<Long> packIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (AiScenarioPackTool entity : packToolMapper.selectList(Wrappers.<AiScenarioPackTool>lambdaQuery()
            .in(AiScenarioPackTool::getPackId, packIds))) {
            counts.merge(entity.getPackId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 统计各场景包的 MCP 工具数量。
     *
     * @param packIds 场景包主键集合
     * @return 场景包主键 → 数量
     */
    private Map<Long, Integer> countMcpToolsByPack(List<Long> packIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (AiScenarioPackMcpTool entity : packMcpToolMapper.selectList(
            Wrappers.<AiScenarioPackMcpTool>lambdaQuery().in(AiScenarioPackMcpTool::getPackId, packIds))) {
            counts.merge(entity.getPackId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 统计各场景包的技能数量。
     *
     * @param packIds 场景包主键集合
     * @return 场景包主键 → 数量
     */
    private Map<Long, Integer> countSkillsByPack(List<Long> packIds) {
        Map<Long, Integer> counts = new HashMap<>();
        for (AiScenarioPackSkill entity : packSkillMapper.selectList(
            Wrappers.<AiScenarioPackSkill>lambdaQuery().in(AiScenarioPackSkill::getPackId, packIds))) {
            counts.merge(entity.getPackId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 拆分 MCP 工具白名单文本（逗号分隔）。
     *
     * @param enableTools 白名单文本
     * @return 工具名列表（去重、保序）
     */
    private List<String> splitTools(String enableTools) {
        if (StringUtils.isBlank(enableTools)) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : enableTools.split(",")) {
            String name = StringUtils.trim(part);
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

}