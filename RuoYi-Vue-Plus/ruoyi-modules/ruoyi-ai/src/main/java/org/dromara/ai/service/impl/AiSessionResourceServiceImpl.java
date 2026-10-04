package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.AiScenarioPackTool;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiSessionResource;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiSessionResourceBatchBo;
import org.dromara.ai.domain.bo.AiSessionResourceItemBo;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.vo.AiSessionResourceVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.ai.mapper.AiScenarioPackMapper;
import org.dromara.ai.mapper.AiSessionResourceMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 会话级资源集 Service 业务层处理（REQ-AI-32）。
 *
 * <p>口径见 {@code script/sql/postgres/postgres_ry_ai.sql} 第四十一部分：</p>
 * <ul>
 *   <li>资源集**对小Z 与专家同时可见**（专家改为继承会话资源集，取代原 {@code pack=null}）；</li>
 *   <li>场景包**会话内可变**、允许并集；{@code ai_session.scenario_pack_id} 停止写入、保留兼容读取；</li>
 *   <li>直接选中工具的默认档位：只读 → 允许、写入 → 需确认（来源标记 {@code session}）；</li>
 *   <li>移除即逻辑删除，场景行删除**不级联**删除已展开的单个资源。</li>
 * </ul>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiSessionResourceServiceImpl implements IAiSessionResourceService {

    private final AiSessionResourceMapper resourceMapper;

    private final AiScenarioPackMapper packMapper;

    private final AiToolMapper toolMapper;

    private final AiSkillMapper skillMapper;

    private final AiMcpClientMapper mcpClientMapper;

    private final IAiScenarioPackService scenarioPackService;

    private final IAiSkillService skillService;

    @Override
    public AiSessionResourceSet resolveForSession(AiSession session) {
        if (session == null || session.getId() == null) {
            return AiSessionResourceSet.empty();
        }
        List<AiSessionResource> rows = queryActiveRows(session.getId());
        List<String> canonical = new ArrayList<>();
        Map<String, String> directToolDecisions = new LinkedHashMap<>();
        List<Long> directSkillIds = new ArrayList<>();
        List<AiScenarioPackMcpTool> directMcpTools = new ArrayList<>();

        // 直接选中的业务工具：档位取「选中时的默认」（只读 → 允许 / 写入 → 需确认），来源标记 session
        List<Long> toolIds = rows.stream()
            .filter(row -> AiSessionResource.TYPE_TOOL.equals(row.getResourceType()))
            .map(AiSessionResource::getResourceId)
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<Long, AiTool> toolById = new HashMap<>();
        if (!toolIds.isEmpty()) {
            for (AiTool tool : toolMapper.selectByIds(toolIds)) {
                toolById.put(tool.getId(), tool);
            }
        }
        for (AiSessionResource row : rows) {
            String type = row.getResourceType();
            if (AiSessionResource.TYPE_TOOL.equals(type)) {
                AiTool tool = toolById.get(row.getResourceId());
                String code = StringUtils.isNotBlank(row.getResourceKey()) ? row.getResourceKey()
                    : (tool == null ? null : tool.getToolCode());
                if (StringUtils.isBlank(code)) {
                    continue;
                }
                boolean readOnly = tool != null && AiTool.TYPE_READ_ONLY.equals(tool.getToolType());
                directToolDecisions.put(code, readOnly
                    ? AiScenarioPackTool.DECISION_ALLOW : AiScenarioPackTool.DECISION_ASK);
                canonical.add("tool:" + code);
            } else if (AiSessionResource.TYPE_SKILL.equals(type)) {
                if (row.getResourceId() == null) {
                    continue;
                }
                directSkillIds.add(row.getResourceId());
                canonical.add("skill:" + row.getResourceId());
            } else if (AiSessionResource.TYPE_MCP_TOOL.equals(type)) {
                if (row.getResourceId() == null || StringUtils.isBlank(row.getResourceKey())) {
                    continue;
                }
                directMcpTools.add(directMcp(row.getResourceId(), row.getResourceKey()));
                canonical.add("mcp:" + row.getResourceId() + "/" + row.getResourceKey());
            }
        }

        // 场景包集合：新表 scenario 行优先；旧会话（无 scenario 行且 scenario_pack_id 非空）按「单包」兼容读取
        boolean hasScenarioRow = rows.stream()
            .anyMatch(row -> AiSessionResource.TYPE_SCENARIO.equals(row.getResourceType()));
        List<AiScenarioPack> packs = new ArrayList<>();
        for (AiSessionResource row : rows) {
            if (!AiSessionResource.TYPE_SCENARIO.equals(row.getResourceType())) {
                continue;
            }
            AiScenarioPack pack = scenarioPackService.getEnabledPackOrNull(row.getResourceId());
            if (pack == null) {
                continue;
            }
            packs.add(pack);
            canonical.add("scenario:" + (StringUtils.isNotBlank(pack.getPackCode())
                ? pack.getPackCode() : String.valueOf(pack.getId())));
        }
        if (!hasScenarioRow && session.getScenarioPackId() != null) {
            AiScenarioPack pack = scenarioPackService.getEnabledPackOrNull(session.getScenarioPackId());
            if (pack != null) {
                packs.add(pack);
                canonical.add("scenario:" + (StringUtils.isNotBlank(pack.getPackCode())
                    ? pack.getPackCode() : String.valueOf(pack.getId())));
            }
        }

        // 展开场景包：包内工具决策 / 技能 / MCP 工具，以及包内容指纹片段
        Map<String, String> packToolDecisions = new LinkedHashMap<>();
        List<Long> packSkillIds = new ArrayList<>();
        List<AiScenarioPackMcpTool> packMcpTools = new ArrayList<>();
        StringBuilder packSignature = new StringBuilder();
        for (AiScenarioPack pack : packs) {
            List<AiScenarioPackTool> tools = scenarioPackService.queryPackTools(pack.getId());
            List<AiScenarioPackMcpTool> mcpTools = scenarioPackService.queryPackMcpTools(pack.getId());
            List<Long> skillIds = scenarioPackService.queryPackSkillIds(pack.getId());
            for (AiScenarioPackTool tool : tools) {
                packToolDecisions.put(tool.getToolCode(), tool.getDecision());
            }
            packSkillIds.addAll(skillIds);
            packMcpTools.addAll(mcpTools);
            packSignature.append(buildPackSignature(pack, tools, mcpTools, skillIds)).append(';');
        }

        return new AiSessionResourceSet(AiSessionResourceSet.signature(canonical), packSignature.toString(),
            packToolDecisions, packSkillIds, packMcpTools, directToolDecisions, directSkillIds, directMcpTools,
            session.getId());
    }

    @Override
    public AiSessionResourceSet ofSinglePack(AiScenarioPack pack) {
        if (pack == null || pack.getId() == null) {
            return AiSessionResourceSet.empty();
        }
        List<AiScenarioPackTool> tools = scenarioPackService.queryPackTools(pack.getId());
        List<AiScenarioPackMcpTool> mcpTools = scenarioPackService.queryPackMcpTools(pack.getId());
        List<Long> skillIds = scenarioPackService.queryPackSkillIds(pack.getId());
        Map<String, String> decisions = new LinkedHashMap<>();
        for (AiScenarioPackTool tool : tools) {
            decisions.put(tool.getToolCode(), tool.getDecision());
        }
        String key = StringUtils.isNotBlank(pack.getPackCode())
            ? pack.getPackCode() : String.valueOf(pack.getId());
        return new AiSessionResourceSet(AiSessionResourceSet.signature(List.of("scenario:" + key)),
            buildPackSignature(pack, tools, mcpTools, skillIds), decisions, skillIds, mcpTools,
            Map.of(), List.of(), List.of(), null);
    }

    @Override
    public List<AiSessionResourceVo> queryResources(Long sessionId) {
        List<AiSessionResource> rows = queryActiveRows(sessionId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, AiScenarioPack> packById = loadPacks(rows);
        Map<Long, AiTool> toolById = loadTools(rows);
        Map<Long, AiSkill> skillById = loadSkills(rows);
        Map<Long, AiMcpClient> clientById = loadClients(rows);

        List<AiSessionResourceVo> result = new ArrayList<>(rows.size());
        for (AiSessionResource row : rows) {
            AiSessionResourceVo vo = new AiSessionResourceVo();
            vo.setId(row.getId());
            vo.setResourceType(row.getResourceType());
            vo.setResourceId(row.getResourceId());
            vo.setResourceKey(row.getResourceKey());
            switch (row.getResourceType()) {
                case AiSessionResource.TYPE_SCENARIO -> {
                    AiScenarioPack pack = packById.get(row.getResourceId());
                    vo.setDisplayName(pack == null ? row.getResourceKey() : pack.getPackName());
                    vo.setSubLabel("场景包");
                }
                case AiSessionResource.TYPE_TOOL -> {
                    AiTool tool = toolById.get(row.getResourceId());
                    vo.setDisplayName(tool == null ? row.getResourceKey() : tool.getToolName());
                    vo.setSubLabel("工具");
                    vo.setToolType(tool == null ? null : tool.getToolType());
                }
                case AiSessionResource.TYPE_SKILL -> {
                    AiSkill skill = skillById.get(row.getResourceId());
                    vo.setDisplayName(skill == null ? row.getResourceKey() : skill.getName());
                    vo.setSubLabel("技能");
                }
                case AiSessionResource.TYPE_MCP_TOOL -> {
                    AiMcpClient client = clientById.get(row.getResourceId());
                    vo.setDisplayName(row.getResourceKey());
                    vo.setSubLabel(client == null ? "MCP 工具" : "MCP · " + client.getServerName());
                }
                default -> vo.setDisplayName(row.getResourceKey());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addResources(Long sessionId, AiSessionResourceBatchBo bo) {
        if (bo == null || bo.getItems() == null || bo.getItems().isEmpty()) {
            return true;
        }
        Long userId = LoginHelper.getUserId();
        for (AiSessionResourceItemBo item : bo.getItems()) {
            AiSessionResource normalized = normalize(item);
            if (normalized == null) {
                continue;
            }
            if (exists(sessionId, normalized.getResourceType(), normalized.getResourceId(),
                normalized.getResourceKey())) {
                // 唯一索引兜底语义：已选中的活跃行直接跳过（幂等）
                continue;
            }
            normalized.setSessionId(sessionId);
            normalized.setCreateBy(userId);
            resourceMapper.insert(normalized);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeResources(Long sessionId, AiSessionResourceBatchBo bo) {
        if (bo == null || bo.getItems() == null || bo.getItems().isEmpty()) {
            return true;
        }
        for (AiSessionResourceItemBo item : bo.getItems()) {
            String type = StringUtils.trim(item == null ? null : item.getResourceType());
            if (item == null || item.getResourceId() == null || !isSupportedType(type)) {
                continue;
            }
            LambdaQueryWrapper<AiSessionResource> lqw = Wrappers.lambdaQuery();
            lqw.eq(AiSessionResource::getSessionId, sessionId);
            lqw.eq(AiSessionResource::getResourceType, type);
            lqw.eq(AiSessionResource::getResourceId, item.getResourceId());
            // MCP 工具无独立主键，需按远端工具名定位到具体一行
            if (AiSessionResource.TYPE_MCP_TOOL.equals(type)) {
                lqw.eq(AiSessionResource::getResourceKey, StringUtils.trim(item.getResourceKey()));
            }
            // 逻辑删除（@TableLogic）即「移除」；不级联删除其它已展开资源
            resourceMapper.delete(lqw);
        }
        return true;
    }

    /**
     * 校验并规范化单项：存在性 + 启用状态（+ 场景包生效范围 / MCP 工具白名单）。
     *
     * @param item 原始项
     * @return 规范化后的实体（未设置会话与审计列）；类型非法或字段缺失时返回 null
     */
    private AiSessionResource normalize(AiSessionResourceItemBo item) {
        if (item == null || item.getResourceId() == null) {
            return null;
        }
        String type = StringUtils.trim(item.getResourceType());
        if (!isSupportedType(type)) {
            throw new ServiceException("不支持的资源类型：" + item.getResourceType());
        }
        AiSessionResource entity = new AiSessionResource();
        entity.setResourceType(type);
        entity.setResourceId(item.getResourceId());
        switch (type) {
            case AiSessionResource.TYPE_SCENARIO -> {
                scenarioPackService.validateSelectable(item.getResourceId());
                AiScenarioPack pack = scenarioPackService.getEnabledPackOrNull(item.getResourceId());
                entity.setResourceKey(pack == null ? String.valueOf(item.getResourceId()) : pack.getPackCode());
            }
            case AiSessionResource.TYPE_TOOL -> {
                AiTool tool = toolMapper.selectById(item.getResourceId());
                if (tool == null || !AiTool.STATUS_ENABLED.equals(tool.getStatus())) {
                    throw new ServiceException("业务工具不存在或已停用：" + item.getResourceId());
                }
                entity.setResourceKey(tool.getToolCode());
            }
            case AiSessionResource.TYPE_SKILL -> {
                AiSkill skill = skillService.getEnabledSkill(item.getResourceId());
                entity.setResourceKey(StringUtils.isNotBlank(skill.getName())
                    ? skill.getName() : "#" + skill.getId());
            }
            case AiSessionResource.TYPE_MCP_TOOL -> {
                String toolName = StringUtils.trim(item.getResourceKey());
                if (StringUtils.isBlank(toolName)) {
                    throw new ServiceException("选择 MCP 工具时必须指定工具名");
                }
                AiMcpClient client = mcpClientMapper.selectById(item.getResourceId());
                if (client == null || !AiMcpClient.STATUS_ENABLED.equals(client.getStatus())) {
                    throw new ServiceException("外部 MCP 连接不存在或已停用：" + item.getResourceId());
                }
                if (!splitTools(client.getEnableTools()).contains(toolName)) {
                    throw new ServiceException("工具不在该 MCP 连接的白名单内：" + client.getServerName()
                        + " / " + toolName);
                }
                entity.setResourceKey(toolName);
            }
            default -> {
                return null;
            }
        }
        return entity;
    }

    /**
     * 判断同样的活跃资源行是否已存在。
     *
     * @param sessionId   会话主键
     * @param type        资源类型
     * @param resourceId  资源主键
     * @param resourceKey 稳定键
     * @return 是否已存在
     */
    private boolean exists(Long sessionId, String type, Long resourceId, String resourceKey) {
        LambdaQueryWrapper<AiSessionResource> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSessionResource::getSessionId, sessionId);
        lqw.eq(AiSessionResource::getResourceType, type);
        lqw.eq(AiSessionResource::getResourceId, resourceId);
        lqw.eq(AiSessionResource::getResourceKey, resourceKey);
        return resourceMapper.selectCount(lqw) > 0;
    }

    /**
     * 查询会话的活跃资源行。
     *
     * @param sessionId 会话主键
     * @return 资源行列表（按主键升序）
     */
    private List<AiSessionResource> queryActiveRows(Long sessionId) {
        LambdaQueryWrapper<AiSessionResource> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSessionResource::getSessionId, sessionId);
        lqw.orderByAsc(AiSessionResource::getId);
        return resourceMapper.selectList(lqw);
    }

    /**
     * 构造直接选中的 MCP 工具项（默认档位「需确认」，由专家侧写工具开关与 HITL 兜底）。
     *
     * @param mcpClientId MCP 连接主键
     * @param toolName    远端工具名
     * @return MCP 工具选择项（packId 为空）
     */
    private AiScenarioPackMcpTool directMcp(Long mcpClientId, String toolName) {
        AiScenarioPackMcpTool selection = new AiScenarioPackMcpTool();
        selection.setMcpClientId(mcpClientId);
        selection.setToolName(toolName);
        selection.setDecision(AiScenarioPackTool.DECISION_ASK);
        return selection;
    }

    /**
     * 生成场景包的装配指纹片段（包本体 + 三类绑定）。
     *
     * @param pack     场景包
     * @param tools    包内业务工具绑定
     * @param mcpTools 包内 MCP 工具绑定
     * @param skillIds 包内技能主键
     * @return 指纹片段
     */
    private String buildPackSignature(AiScenarioPack pack, List<AiScenarioPackTool> tools,
                                      List<AiScenarioPackMcpTool> mcpTools, List<Long> skillIds) {
        StringBuilder signature = new StringBuilder();
        signature.append(pack.getId()).append(':').append(pack.getUpdateTime())
            .append(':').append(pack.getStatus());
        for (AiScenarioPackTool tool : tools) {
            signature.append("|t:").append(tool.getToolCode()).append('=').append(tool.getDecision());
        }
        for (AiScenarioPackMcpTool mcpTool : mcpTools) {
            signature.append("|m:").append(mcpTool.getMcpClientId()).append('/')
                .append(mcpTool.getToolName()).append('=').append(mcpTool.getDecision());
        }
        signature.append("|s:").append(skillIds);
        return signature.toString();
    }

    /**
     * 批量加载资源行涉及到的场景包。
     */
    private Map<Long, AiScenarioPack> loadPacks(List<AiSessionResource> rows) {
        Set<Long> ids = collectIds(rows, AiSessionResource.TYPE_SCENARIO);
        Map<Long, AiScenarioPack> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AiScenarioPack pack : packMapper.selectByIds(ids)) {
                result.put(pack.getId(), pack);
            }
        }
        return result;
    }

    /**
     * 批量加载资源行涉及到的业务工具。
     */
    private Map<Long, AiTool> loadTools(List<AiSessionResource> rows) {
        Set<Long> ids = collectIds(rows, AiSessionResource.TYPE_TOOL);
        Map<Long, AiTool> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AiTool tool : toolMapper.selectByIds(ids)) {
                result.put(tool.getId(), tool);
            }
        }
        return result;
    }

    /**
     * 批量加载资源行涉及到的技能。
     */
    private Map<Long, AiSkill> loadSkills(List<AiSessionResource> rows) {
        Set<Long> ids = collectIds(rows, AiSessionResource.TYPE_SKILL);
        Map<Long, AiSkill> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AiSkill skill : skillMapper.selectByIds(ids)) {
                result.put(skill.getId(), skill);
            }
        }
        return result;
    }

    /**
     * 批量加载资源行涉及到的 MCP 连接。
     */
    private Map<Long, AiMcpClient> loadClients(List<AiSessionResource> rows) {
        Set<Long> ids = collectIds(rows, AiSessionResource.TYPE_MCP_TOOL);
        Map<Long, AiMcpClient> result = new HashMap<>();
        if (!ids.isEmpty()) {
            for (AiMcpClient client : mcpClientMapper.selectByIds(ids)) {
                result.put(client.getId(), client);
            }
        }
        return result;
    }

    /**
     * 收集指定类型的资源主键集合。
     */
    private Set<Long> collectIds(List<AiSessionResource> rows, String type) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AiSessionResource row : rows) {
            if (type.equals(row.getResourceType()) && row.getResourceId() != null) {
                ids.add(row.getResourceId());
            }
        }
        return ids;
    }

    /**
     * 判断是否为受支持的资源类型。
     *
     * @param type 资源类型
     * @return 是否受支持
     */
    private boolean isSupportedType(String type) {
        return AiSessionResource.TYPE_SCENARIO.equals(type)
            || AiSessionResource.TYPE_TOOL.equals(type)
            || AiSessionResource.TYPE_SKILL.equals(type)
            || AiSessionResource.TYPE_MCP_TOOL.equals(type);
    }

    /**
     * 拆分 MCP 连接的工具白名单文本（逗号分隔）。
     *
     * @param enableTools 白名单文本
     * @return 工具名列表（去重保序）
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