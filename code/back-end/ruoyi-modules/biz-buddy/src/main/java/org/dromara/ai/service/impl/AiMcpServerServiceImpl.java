package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.agent.AiToolFactory;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.vo.AiMcpServerToolVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mcp.config.McpServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 出口（服务端）Service 业务层处理（REQ-AI-15）。
 *
 * <p>暴露清单复用业务工具注册表，故「有哪些工具可暴露」与「装配进智能体的工具」天然同源；
 * 出口只取**启用中的只读工具**（{@code tool_type='0'}）——写入类工具在出口没有人工确认闸门，
 * 一律不允许暴露。</p>
 *
 * <p>热更新走官方 MCP SDK 的 {@code McpStatelessSyncServer#addTool / removeTool}：
 * 该 API 为公开可变接口，因此保存清单后无需重建出口、也无需重启进程。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMcpServerServiceImpl implements IAiMcpServerService {

    /**
     * 出口工具执行的同步等待上限；超时按失败回喂调用方，避免外部请求无限挂起
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(120);

    private final AiToolMapper toolMapper;

    private final AiToolFactory toolFactory;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * MCP 出口服务端（{@code mcp.server.enabled=false} 时不存在，故用 ObjectProvider 容忍缺失）
     */
    private final ObjectProvider<McpStatelessSyncServer> mcpServerProvider;

    /**
     * MCP 出口属性（用于回显端点路径）
     */
    private final ObjectProvider<McpServerProperties> mcpServerPropertiesProvider;

    /**
     * 安全合规守卫（MCP 出口入参 / 出参过审）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * 安全事件留痕
     */
    private final IAiSafetyEventService safetyEventService;

    /**
     * 已由本服务挂载到出口的业务工具名 → 挂载时的工具更新时间。
     *
     * <p>只记录本服务 {@code addTool} 过的名字，因此增量撤销时不会误伤平台内置工具
     * （如 ruoyi-demo 注册的演示工具）。</p>
     */
    private final Map<String, String> applied = new ConcurrentHashMap<>();

    @Override
    public AiMcpServerVo queryServer() {
        AiMcpServerConfigBo config = runtimeConfigStore.mcpServer();
        AiMcpServerVo vo = new AiMcpServerVo();
        McpServerProperties properties = mcpServerPropertiesProvider.getIfAvailable();
        vo.setEndpoint(properties == null ? null : properties.getEndpoint());
        vo.setAuthEnabled(config.isAuthEnabled());
        vo.setToken(config.getToken());
        McpStatelessSyncServer server = mcpServerProvider.getIfAvailable();
        vo.setLiveTools(server == null ? List.of()
            : server.listTools().stream().map(McpSchema.Tool::name).sorted().toList());
        return vo;
    }

    @Override
    public List<AiMcpServerToolVo> queryTools() {
        List<AiMcpServerToolVo> result = new ArrayList<>();
        for (AiTool tool : queryReadonlyEnabled()) {
            AiMcpServerToolVo vo = new AiMcpServerToolVo();
            vo.setId(tool.getId());
            vo.setToolCode(tool.getToolCode());
            vo.setToolName(tool.getToolName());
            vo.setDescription(tool.getDescription());
            vo.setToolType(tool.getToolType());
            vo.setExposed(AiTool.EXPOSED_YES.equals(tool.getMcpExposed()));
            vo.setRegistered(toolFactory.isSupported(tool.getImplName()));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExposed(List<String> toolCodes) {
        Set<String> target = toolCodes == null ? Set.of() : new LinkedHashSet<>(toolCodes);
        List<AiTool> candidates = queryReadonlyEnabled();
        Set<String> candidateCodes = new LinkedHashSet<>();
        candidates.forEach(tool -> candidateCodes.add(tool.getToolCode()));
        List<String> invalid = target.stream().filter(code -> !candidateCodes.contains(code)).toList();
        if (!invalid.isEmpty()) {
            throw new ServiceException("以下工具不支持对外暴露（仅启用中的只读工具可暴露）：" + String.join("、", invalid));
        }
        for (AiTool tool : candidates) {
            String exposed = target.contains(tool.getToolCode()) ? AiTool.EXPOSED_YES : AiTool.EXPOSED_NO;
            if (exposed.equals(tool.getMcpExposed())) {
                continue;
            }
            AiTool update = new AiTool();
            update.setId(tool.getId());
            update.setMcpExposed(exposed);
            toolMapper.updateById(update);
        }
        reconcile();
    }

    @Override
    public void reconcile() {
        McpStatelessSyncServer server = mcpServerProvider.getIfAvailable();
        if (server == null) {
            log.debug("MCP 出口未启用（无 McpStatelessSyncServer Bean），跳过暴露清单同步");
            return;
        }
        Set<String> live = new LinkedHashSet<>(server.listTools().stream().map(McpSchema.Tool::name).toList());
        Map<String, AiTool> desired = new LinkedHashMap<>();
        for (AiTool tool : queryExposedEnabled()) {
            desired.put(tool.getToolCode(), tool);
        }
        // 撤销：此前由本服务挂载、现已不再暴露（取消勾选 / 停用 / 改为写入类 / 删除）的工具
        for (String name : new LinkedHashSet<>(applied.keySet())) {
            if (desired.containsKey(name)) {
                continue;
            }
            try {
                if (live.contains(name)) {
                    server.removeTool(name);
                }
                applied.remove(name);
                log.info("MCP 出口已撤销工具：{}", name);
            } catch (Exception e) {
                log.error("MCP 出口撤销工具失败：{}", name, e);
            }
        }
        // 挂载：新暴露的工具，或定义已变更（描述 / 参数 Schema）需要刷新的工具
        for (AiTool tool : desired.values()) {
            String code = tool.getToolCode();
            String stamp = String.valueOf(tool.getUpdateTime());
            if (live.contains(code) && stamp.equals(applied.get(code))) {
                continue;
            }
            if (!toolFactory.isSupported(tool.getImplName())) {
                log.warn("MCP 出口跳过工具（实现未在代码侧登记）：code={}, implName={}", code, tool.getImplName());
                continue;
            }
            try {
                if (live.contains(code)) {
                    server.removeTool(code);
                }
                server.addTool(toSpecification(tool));
                applied.put(code, stamp);
                log.info("MCP 出口已挂载工具：code={}, description={}", code, tool.getDescription());
            } catch (Exception e) {
                log.error("MCP 出口挂载工具失败：{}", code, e);
            }
        }
    }

    /**
     * 应用就绪后把已暴露的工具挂载到出口（进程重启后出口重建，需重新挂载）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initExposedTools() {
        reconcile();
    }

    /**
     * 由业务工具定义构造 MCP 出口工具规格。
     *
     * <p>工具名取 {@code tool_code}，参数 Schema 由 {@code params_schema}（JSON Schema）解析而来；
     * 调用时按编码回读最新定义，因此描述/参数变更无需重启即可生效。</p>
     *
     * @param tool 业务工具定义
     * @return MCP 无状态同步工具规格
     */
    private McpStatelessServerFeatures.SyncToolSpecification toSpecification(AiTool tool) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
            .name(tool.getToolCode())
            .description(tool.getDescription())
            .inputSchema(parseSchema(tool.getParamsSchema()))
            .build();
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(mcpTool)
            .callHandler((context, request) -> invoke(tool.getToolCode(), request))
            .build();
    }

    /**
     * 执行出口工具：把业务工具（{@link org.dromara.ai.core.agent.AiToolFactory} 登记的实现）
     * 包成 MCP 调用的同步结果。
     *
     * @param toolCode 工具编码
     * @param request  MCP 调用请求
     * @return MCP 调用结果
     */
    private McpSchema.CallToolResult invoke(String toolCode, McpSchema.CallToolRequest request) {
        AiTool definition = queryByCode(toolCode);
        if (definition == null) {
            return errorResult("工具不存在或已被删除：" + toolCode);
        }
        // 出口调用方只携带平台口令、没有平台用户：只读检索类工具按「公开且启用」的知识库绑定
        List<Long> kbIds = knowledgeBaseService.listPublicEnabledKbIds();
        Optional<AgentTool> agentTool = toolFactory.create(definition, kbIds, true);
        if (agentTool.isEmpty()) {
            return errorResult("工具实现未在代码侧登记：" + definition.getImplName());
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        // 安全合规（MCP_OUT 入参）：序列化后过审，命中阻断则不执行工具
        String argJson = JsonUtils.toJsonString(arguments);
        SafetyDecision argDecision = safetyGuard.guard(argJson, SafetyScene.MCP_OUT);
        if (argDecision.hit()) {
            safetyEventService.record(SafetyScene.MCP_OUT, argDecision, safetyGuard.engineName(),
                null, null, null, null, toolCode + "｜" + argJson);
        }
        if (argDecision.blocked()) {
            return errorResult("请求包含不允许的内容，已拒绝");
        }
        try {
            ToolUseBlock use = ToolUseBlock.builder()
                .id("mcp-" + UUID.randomUUID())
                .name(toolCode)
                .input(arguments)
                .build();
            ToolResultBlock result = agentTool.get()
                .callAsync(ToolCallParam.builder().toolUseBlock(use).input(arguments).build())
                .block(TOOL_TIMEOUT);
            if (result == null) {
                return errorResult("工具执行超时：" + toolCode);
            }
            return guardOutput(toCallResult(result), toolCode);
        } catch (Exception e) {
            log.error("MCP 出口执行工具失败：code={}, args={}", toolCode, arguments, e);
            return errorResult("工具执行失败：" + e.getMessage());
        }
    }

    /**
     * 对 MCP 出口结果文本过「MCP_OUT」守卫：阻断以拦截文案替代，命中脱敏后替换文本。
     *
     * @param callResult 工具执行结果
     * @param toolCode   工具编码（留痕用）
     * @return 过审后的结果
     */
    private McpSchema.CallToolResult guardOutput(McpSchema.CallToolResult callResult, String toolCode) {
        String output = extractText(callResult);
        SafetyDecision decision = safetyGuard.guard(output, SafetyScene.MCP_OUT);
        if (decision.hit()) {
            safetyEventService.record(SafetyScene.MCP_OUT, decision, safetyGuard.engineName(),
                null, null, null, null, toolCode + "｜" + output);
        }
        if (decision.blocked()) {
            return errorResult("结果包含不允许的内容，已拦截");
        }
        if (decision.text() == null || decision.text().equals(output)) {
            return callResult;
        }
        return McpSchema.CallToolResult.builder()
            .addTextContent(decision.text())
            .isError(Boolean.TRUE.equals(callResult.isError()))
            .build();
    }

    /**
     * 提取 MCP 调用结果中的文本内容。
     *
     * @param callResult 工具执行结果
     * @return 拼接后的文本（无文本时为空串）
     */
    private String extractText(McpSchema.CallToolResult callResult) {
        if (callResult.content() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : callResult.content()) {
            if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    /**
     * 把 AgentScope 工具结果转换为 MCP 调用结果。
     *
     * @param result AgentScope 工具结果
     * @return MCP 调用结果
     */
    private McpSchema.CallToolResult toCallResult(ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textBlock.getText());
            }
        }
        return McpSchema.CallToolResult.builder()
            .addTextContent(text.toString())
            .isError(ToolResultState.ERROR == result.getState())
            .build();
    }

    /**
     * 构造失败结果。
     *
     * @param message 失败原因
     * @return MCP 调用结果（isError=true）
     */
    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .isError(true)
            .build();
    }

    /**
     * 解析业务工具的 JSON Schema 参数定义为 MCP 参数定义。
     *
     * @param paramsSchema JSON Schema 文本
     * @return MCP 参数定义（无定义时返回空对象 Schema）
     */
    private McpSchema.JsonSchema parseSchema(String paramsSchema) {
        Map<String, Object> schema = null;
        if (StringUtils.isNotBlank(paramsSchema) && JsonUtils.isJsonObject(paramsSchema)) {
            schema = JsonUtils.parseObject(paramsSchema, new TypeReference<Map<String, Object>>() {
            });
        }
        if (schema == null || schema.isEmpty()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        }
        String type = schema.get("type") == null ? "object" : String.valueOf(schema.get("type"));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (schema.get("properties") instanceof Map<?, ?> raw) {
            raw.forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> rawList) {
            rawList.forEach(item -> required.add(String.valueOf(item)));
        }
        return new McpSchema.JsonSchema(type, properties, required, null, null, null);
    }

    /**
     * 查询启用中的只读工具（出口候选清单）。
     *
     * @return 工具定义列表
     */
    private List<AiTool> queryReadonlyEnabled() {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getToolType, AiTool.TYPE_READ_ONLY);
        lqw.eq(AiTool::getStatus, AiTool.STATUS_ENABLED);
        lqw.orderByAsc(AiTool::getToolCode);
        return toolMapper.selectList(lqw);
    }

    /**
     * 查询「已标记暴露且仍可暴露」的工具（启用中的只读工具）。
     *
     * @return 工具定义列表
     */
    private List<AiTool> queryExposedEnabled() {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getToolType, AiTool.TYPE_READ_ONLY);
        lqw.eq(AiTool::getStatus, AiTool.STATUS_ENABLED);
        lqw.eq(AiTool::getMcpExposed, AiTool.EXPOSED_YES);
        lqw.orderByAsc(AiTool::getToolCode);
        return toolMapper.selectList(lqw);
    }

    /**
     * 按编码查询工具定义。
     *
     * @param toolCode 工具编码
     * @return 工具定义，不存在时返回 null
     */
    private AiTool queryByCode(String toolCode) {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getToolCode, toolCode);
        return toolMapper.selectOne(lqw);
    }

}