package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgentSubagent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiSubagentBo;
import org.dromara.ai.domain.vo.AiSubagentOptionVo;
import org.dromara.ai.domain.vo.AiSubagentVo;
import org.dromara.ai.mapper.AiAgentSubagentMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiSubagentMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiSubagentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
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
 * AI 子智能体 Service 业务层处理（REQ-AI-04）
 *
 * <p>工具白名单以 JSON 数组文本落库；保存时强制校验「工具已登记且为只读」——
 * 框架只为子智能体继承父级的 deny 规则（allow / ask 不继承），子智能体内没有人工确认闸门，
 * 因此写入类工具不得下放（见 docs/00 第 42 项）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiSubagentServiceImpl implements IAiSubagentService {

    private final AiSubagentMapper baseMapper;

    private final AiAgentSubagentMapper agentSubagentMapper;

    private final AiToolMapper toolMapper;

    private final AiModelConfigMapper modelConfigMapper;

    private final UserService userService;

    @Override
    public PageResult<AiSubagentVo> queryPageList(AiSubagentBo bo, PageQuery pageQuery) {
        Page<AiSubagentVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiSubagentVo> records = result.getRecords();
        fillDisplayFields(records);
        return PageResult.build(records, result.getTotal());
    }

    @Override
    public AiSubagentVo queryById(Long id) {
        AiSubagentVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("子智能体不存在或已被删除");
        }
        fillDisplayFields(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiSubagentBo bo) {
        AiSubagent add = MapstructUtils.convert(bo, AiSubagent.class);
        add.setName(normalizeName(bo.getName()));
        add.setDescription(StringUtils.trim(bo.getDescription()));
        applyDefaults(add);
        add.setToolCodes(serializeToolCodes(bo.getToolCodeList()));
        validNameUnique(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiSubagentBo bo) {
        AiSubagent exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("子智能体不存在或已被删除");
        }
        String name = normalizeName(bo.getName());
        if (!exists.getName().equals(name)) {
            throw new ServiceException("子智能体标识不允许修改（已作为 AgentScope 的 agent_id 使用）");
        }
        AiSubagent update = MapstructUtils.convert(bo, AiSubagent.class);
        update.setName(name);
        update.setDescription(StringUtils.trim(bo.getDescription()));
        applyDefaults(update);
        update.setToolCodes(serializeToolCodes(bo.getToolCodeList()));
        return baseMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的子智能体");
        }
        List<AiSubagent> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("子智能体不存在或已被删除");
        }
        LambdaQueryWrapper<AiAgentSubagent> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSubagent::getSubagentId, ids);
        if (agentSubagentMapper.selectCount(lqw) > 0) {
            throw new ServiceException("该子智能体已被智能体绑定，请先在智能体定义中解除绑定");
        }
        // 物理删除：唯一索引需支持删除后复用同名标识
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public List<AiSubagentOptionVo> queryOptions() {
        LambdaQueryWrapper<AiSubagent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSubagent::getStatus, AiSubagent.STATUS_ENABLED);
        lqw.orderByDesc(AiSubagent::getUpdateTime);
        List<AiSubagentOptionVo> options = new ArrayList<>();
        // 逐字段手工映射：MapStruct 只为「实体 → 视图对象」生成转换器，VO → VO 无转换器可用
        for (AiSubagent entity : baseMapper.selectList(lqw)) {
            AiSubagentOptionVo option = new AiSubagentOptionVo();
            option.setId(entity.getId());
            option.setName(entity.getName());
            option.setDescription(entity.getDescription());
            option.setWorkspaceMode(entity.getWorkspaceMode());
            option.setSteps(entity.getSteps());
            option.setToolCodeList(parseToolCodes(entity.getToolCodes()));
            options.add(option);
        }
        return options;
    }

    @Override
    public AiSubagent getEnabledSubagent(Long id) {
        AiSubagent subagent = id == null ? null : baseMapper.selectById(id);
        if (subagent == null) {
            throw new ServiceException("子智能体不存在或已被删除");
        }
        if (!AiSubagent.STATUS_ENABLED.equals(subagent.getStatus())) {
            throw new ServiceException("子智能体已停用：" + subagent.getName());
        }
        return subagent;
    }

    @Override
    public List<AiSubagent> queryBoundEnabledSubagents(Long agentId) {
        List<Long> bound = queryBoundSubagentIds(agentId);
        if (bound.isEmpty()) {
            return List.of();
        }
        Map<Long, AiSubagent> byId = new LinkedHashMap<>();
        for (AiSubagent subagent : baseMapper.selectByIds(bound)) {
            byId.put(subagent.getId(), subagent);
        }
        List<AiSubagent> result = new ArrayList<>(bound.size());
        for (Long id : bound) {
            AiSubagent subagent = byId.get(id);
            // 绑定后又被停用 / 删除的子智能体不参与装配
            if (subagent != null && AiSubagent.STATUS_ENABLED.equals(subagent.getStatus())) {
                result.add(subagent);
            }
        }
        return result;
    }

    @Override
    public List<Long> queryBoundSubagentIds(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiAgentSubagent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentSubagent::getAgentId, agentId);
        lqw.orderByAsc(AiAgentSubagent::getId);
        return agentSubagentMapper.selectList(lqw).stream().map(AiAgentSubagent::getSubagentId).toList();
    }

    @Override
    public Map<Long, List<Long>> queryAgentSubagentMap(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiAgentSubagent> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSubagent::getAgentId, agentIds);
        lqw.orderByAsc(AiAgentSubagent::getId);
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (AiAgentSubagent entity : agentSubagentMapper.selectList(lqw)) {
            result.computeIfAbsent(entity.getAgentId(), key -> new ArrayList<>()).add(entity.getSubagentId());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAgentBindings(Long agentId, Collection<Long> subagentIds) {
        LambdaQueryWrapper<AiAgentSubagent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentSubagent::getAgentId, agentId);
        agentSubagentMapper.delete(lqw);
        if (subagentIds == null || subagentIds.isEmpty()) {
            return;
        }
        for (Long subagentId : new LinkedHashSet<>(subagentIds)) {
            AiAgentSubagent entity = new AiAgentSubagent();
            entity.setAgentId(agentId);
            entity.setSubagentId(subagentId);
            agentSubagentMapper.insert(entity);
        }
    }

    @Override
    public void deleteAgentBindings(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<AiAgentSubagent> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSubagent::getAgentId, agentIds);
        agentSubagentMapper.delete(lqw);
    }

    /**
     * 校验子智能体标识格式并规范化。
     *
     * @param raw 原始标识
     * @return 规范化后的标识
     */
    private String normalizeName(String raw) {
        String name = StringUtils.trim(raw).toLowerCase();
        if (!name.matches("^[a-z][a-z0-9_-]{1,60}$")) {
            throw new ServiceException("子智能体标识只能包含小写字母、数字、下划线或短横线，且以字母开头（2~61 位）");
        }
        return name;
    }

    /**
     * 填充默认值并校验工作区模式取值。
     *
     * @param entity 实体
     */
    private void applyDefaults(AiSubagent entity) {
        String mode = entity.getWorkspaceMode() == null ? null : StringUtils.trim(entity.getWorkspaceMode());
        if (StringUtils.isBlank(mode)) {
            mode = AiSubagent.WORKSPACE_ISOLATED;
        } else {
            mode = mode.toUpperCase();
        }
        if (!AiSubagent.WORKSPACE_ISOLATED.equals(mode) && !AiSubagent.WORKSPACE_SHARED.equals(mode)) {
            throw new ServiceException("工作区模式只能是 ISOLATED（隔离）或 SHARED（共享）");
        }
        entity.setWorkspaceMode(mode);
        if (entity.getSteps() == null || entity.getSteps() <= 0) {
            entity.setSteps(AiSubagent.DEFAULT_STEPS);
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(AiSubagent.STATUS_ENABLED);
        }
    }

    /**
     * 校验子智能体标识唯一。
     *
     * @param entity 待保存实体
     */
    private void validNameUnique(AiSubagent entity) {
        LambdaQueryWrapper<AiSubagent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSubagent::getName, entity.getName());
        lqw.ne(entity.getId() != null, AiSubagent::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("子智能体标识已存在：" + entity.getName());
        }
    }

    /**
     * 校验工具白名单并序列化为 JSON 数组文本。
     *
     * <p>只允许**已登记且为只读**的工具：框架不为子智能体继承父级的 allow / ask 规则
     * （仅继承 deny），子智能体内没有人工确认闸门，写入类工具一旦下放将绕过确认直接执行。</p>
     *
     * @param toolCodes 工具编码集合
     * @return JSON 数组文本（无工具时返回 null）
     */
    private String serializeToolCodes(Collection<String> toolCodes) {
        List<String> codes = normalizeToolCodes(toolCodes);
        return codes.isEmpty() ? null : JsonUtils.toJsonString(codes);
    }

    /**
     * 去重并校验工具编码（存在 + 只读）。
     *
     * @param toolCodes 工具编码集合
     * @return 规范化后的工具编码列表（保持入参顺序）
     */
    private List<String> normalizeToolCodes(Collection<String> toolCodes) {
        if (toolCodes == null || toolCodes.isEmpty()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(toolCodes)) {
            String code = StringUtils.trim(raw);
            if (StringUtils.isBlank(code)) {
                continue;
            }
            AiTool tool = toolMapper.selectOne(Wrappers.<AiTool>lambdaQuery().eq(AiTool::getToolCode, code));
            if (tool == null) {
                throw new ServiceException("工具未在「业务工具注册」中登记，不能加入子智能体工具白名单：" + code);
            }
            if (!AiTool.TYPE_READ_ONLY.equals(tool.getToolType())) {
                throw new ServiceException("写入类工具不能下放给子智能体（子智能体侧没有人工确认闸门）："
                    + tool.getToolName());
            }
            codes.add(code);
        }
        return codes;
    }

    /**
     * 解析工具编码 JSON 数组文本。
     *
     * @param json JSON 数组文本
     * @return 工具编码列表（空或非法时返回空列表）
     */
    private List<String> parseToolCodes(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> codes = JsonUtils.parseArray(json, String.class);
            return codes == null ? List.of() : codes;
        } catch (Exception e) {
            log.warn("子智能体工具白名单解析失败：{}", json);
            return List.of();
        }
    }

    /**
     * 构建子智能体动态查询条件（管理页全量）。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiSubagent> buildQueryWrapper(AiSubagentBo bo) {
        LambdaQueryWrapper<AiSubagent> lqw = Wrappers.lambdaQuery();
        lqw.and(StringUtils.isNotBlank(bo.getKeyword()), w -> w
            .like(AiSubagent::getName, bo.getKeyword())
            .or().like(AiSubagent::getDescription, bo.getKeyword()));
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiSubagent::getStatus, bo.getStatus());
        lqw.orderByDesc(AiSubagent::getUpdateTime);
        return lqw;
    }

    /**
     * 填充工具白名单数组、工具显示名、模型名称与创建人昵称。
     *
     * @param list 子智能体列表
     */
    private void fillDisplayFields(List<AiSubagentVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> allCodes = new LinkedHashSet<>();
        Set<Long> modelIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AiSubagentVo vo : list) {
            List<String> codes = parseToolCodes(vo.getToolCodes());
            vo.setToolCodeList(codes);
            allCodes.addAll(codes);
            if (vo.getModelId() != null) {
                modelIds.add(vo.getModelId());
            }
            if (vo.getCreateBy() != null) {
                userIds.add(vo.getCreateBy());
            }
        }
        Map<String, String> toolNames = queryToolNames(allCodes);
        Map<Long, String> modelNames = queryModelNames(modelIds);
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        for (AiSubagentVo vo : list) {
            vo.setToolNames(vo.getToolCodeList().stream()
                .map(code -> toolNames.getOrDefault(code, code)).toList());
            // 注意：不可变 Map（Map.of）对 null 键会抛 NPE，取值前必须先判空
            vo.setModelName(vo.getModelId() == null ? null : modelNames.get(vo.getModelId()));
            vo.setCreateByName(vo.getCreateBy() == null ? null : userNames.get(vo.getCreateBy()));
        }
    }

    /**
     * 查询工具编码 → 显示名映射。
     *
     * @param codes 工具编码集合
     * @return 映射
     */
    private Map<String, String> queryToolNames(Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.in(AiTool::getToolCode, codes);
        Map<String, String> names = new HashMap<>();
        for (AiTool tool : toolMapper.selectList(lqw)) {
            names.put(tool.getToolCode(), tool.getToolName());
        }
        return names;
    }

    /**
     * 查询模型主键 → 模型标识映射。
     *
     * @param modelIds 模型主键集合
     * @return 映射
     */
    private Map<Long, String> queryModelNames(Set<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (AiModelConfig config : modelConfigMapper.selectByIds(modelIds)) {
            names.put(config.getId(), config.getModelCode());
        }
        return names;
    }

}