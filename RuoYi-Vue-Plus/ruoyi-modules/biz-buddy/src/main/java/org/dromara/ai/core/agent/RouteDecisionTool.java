package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由决策工具（REQ-AI-30）：入口智能体（小Z）通过**工具调用**提交决策，不解析自由文本。
 *
 * <p>决策结构化提交的好处：字段可强校验（候选必须来自本轮清单）、可留档复盘
 * （{@code reason} / {@code confidence} 进 {@code ai_route_run.decision_json}）、
 * 且不需要正则去猜模型想干什么。</p>
 *
 * <p><b>执行方式</b>：{@code action=route} 时本工具**就地完成整条专家编排**
 * （调用 → 契约校验 → 分级处置 → 换专家），把专家结论作为**工具结果**回喂入口智能体，
 * 于是小Z 会在同一轮 ReAct 循环里接着做整合轮——不需要额外的编排驱动。</p>
 *
 * <p><b>线程</b>：专家调用是阻塞式同步等待（沿用子智能体的强制同步与硬超时口径），
 * 故放到 {@code boundedElastic} 上执行，避免占用框架的工具执行线程。</p>
 *
 * <p><b>护栏</b>：候选必须来自清单；深度恒为 0（专家不再路由）；工具未被调用 / 参数非法 / 超时
 * 一律降级为小Z 自答——不牺牲可用性。</p>
 *
 * @author BizBuddy
 */
class RouteDecisionTool extends ToolBase {

    /**
     * 工具名（编排协议内部工具，不在 ai_tool 业务工具注册表中登记）
     */
    static final String TOOL_NAME = "route_decision";

    /**
     * 工具描述：把「什么时候该调」与「task_context 只写片段」写进描述里（描述会进模型视野）
     */
    private static final String DESCRIPTION =
        "把任务交给专家。先自行判断：能准确回答的就不要调用本工具（action=self）；"
            + "自己回答不了、或专家明显更专业时才用 action=route。"
            + "候选专家清单见每轮注入的上下文，expert_agent_key 必须来自该清单。"
            + "task_context 只写本任务必需的事实片段，不要把整段对话历史搬进去；宁可少写。"
            + "若交给专家，专家会**直接回答用户**，你本轮不要再组织语言。";

    private final AiRouteOrchestrator orchestrator;

    /**
     * @param orchestrator 专家编排器（装配专家、执行分级处置、落库与下发 SSE 事件）
     */
    RouteDecisionTool(AiRouteOrchestrator orchestrator) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.orchestrator = orchestrator;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        final String id = use == null ? null : use.getId();
        final String name = use == null || StringUtils.isBlank(use.getName()) ? TOOL_NAME : use.getName();
        RuntimeContext context = param == null ? null : param.getRuntimeContext();
        AiRouteContext route = context == null ? null : context.get(AiRouteContext.class);
        if (route == null) {
            // 普通智能体对话 / 无人值守链路：本工具不可用，让模型照常作答
            return Mono.just(ToolResultBlock
                .error("当前不在编排链路，本工具不可用；请直接回答用户的问题。")
                .withIdAndName(id, name));
        }
        // 整合轮（恢复段拿到专家结论后驱动入口智能体做归纳）：服务端强约束不再路由，
        // 避免「整合轮又发起一次编排」把链路拖长（REQ-AI-30 Phase 2 HITL 贯通）
        if (route.routeDone) {
            return Mono.just(ToolResultBlock
                .error("本轮为整合轮，禁止再路由；请直接输出最终回答。")
                .withIdAndName(id, name));
        }
        // 本轮编排已因「需确认」暂停：不再接受新的路由请求，等用户确认后由恢复段续跑。
        // 终止口径（2026-10-04 修正）：护栏必须给出**可收敛的动作**——模型返回不带工具调用的纯文本
        // 才会结束 ReAct 循环；若只说「不要输出任何内容」，模型无终止动作可做，会反复调用本工具
        // 直到 maxIters（实测：同一轮重复调用十余次、整轮耗时 ~50s）。
        if (route.pendingResume != null) {
            return Mono.just(ToolResultBlock
                .text("本轮编排已暂停，正在等待用户确认。请立即停止调用工具，"
                    + "输出一句极简说明（如「已提交，等待你确认」）即可。")
                .withIdAndName(id, name));
        }
        // 专家直答已接管（REQ-AI-30 直答修订）：专家正文已展示给用户，不允许再发起一次编排。
        // 同样给出终止动作：让模型返回纯文本以结束本轮循环（该结束语由平台抑制，不展示给用户）。
        if (route.handoff) {
            return Mono.just(ToolResultBlock
                .text("专家已直接回答用户，本轮任务已完成。请立即停止调用工具，"
                    + "直接输出一句极简结束语（如「（已由专家回答）」）即可，不要复述专家内容。")
                .withIdAndName(id, name));
        }
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        route.routeInvoked = true;
        // 指定专家（固化路由，REQ-AI-31）：**用户意志覆盖模型决策**——忽略模型传入的
        // action / expert_agent_key，直接按会话指定的专家执行完整编排并落 action=pinned。
        // 服务端强约束，不依赖模型自觉（提示词仅用于减少「收尾兜底」的触发）。
        if (route.pinnedExpertId != null) {
            return runPinned(route, id, name, input);
        }
        String action = text(input.get("action"));
        route.action = action;
        route.decisionJson = JsonUtils.toJsonString(input);
        if (AiRouteRun.ACTION_SELF.equals(action)) {
            return Mono.just(ToolResultBlock
                .text("已记录你的决定：本轮由你自己回答。请直接给出回答，不要再调用本工具。")
                .withIdAndName(id, name));
        }
        if (!AiRouteRun.ACTION_ROUTE.equals(action)) {
            route.degraded = true;
            route.degradeReason = "决策参数非法：action=" + action;
            return Mono.just(ToolResultBlock
                .error("action 只能是 self 或 route；本轮请直接回答用户的问题。")
                .withIdAndName(id, name));
        }
        AiExpertCandidate candidate = route.candidate(text(input.get("expert_agent_key")));
        if (candidate == null) {
            route.degraded = true;
            route.degradeReason = "决策参数非法：候选专家不在本轮清单内";
            return Mono.just(ToolResultBlock
                .error("expert_agent_key 必须来自本轮候选专家清单；本轮请直接回答用户的问题。")
                .withIdAndName(id, name));
        }
        route.taskContext = jsonText(input.get("task_context"));
        return Mono.fromCallable(() -> orchestrator.execute(route, candidate))
            .subscribeOn(Schedulers.boundedElastic())
            .map(report -> ToolResultBlock.text(report).withIdAndName(id, name))
            .onErrorResume(error -> {
                String message = AiSseSupport.rootCauseMessage(error);
                route.degraded = true;
                route.degradeReason = "编排异常：" + message;
                return Mono.just(ToolResultBlock
                    .error("专家调用失败：" + message
                        + "。请如实告知用户「未能拿到专家结论」，然后基于你自己掌握的信息回答，不要编造专家结论。")
                    .withIdAndName(id, name));
            });
    }

    /**
     * 指定专家（固化路由，REQ-AI-31）的执行：忽略模型入参，按会话指定的专家就地完成编排。
     *
     * <p>指定路由下 {@code task_context} 恒为「用户原话 + 槽位」（P7），由门面在建编排上下文时预置，
     * 模型无权改写；本路径**不取模型任何入参**（专家直答修订后已无 output_contract）。</p>
     *
     * <p>决策原文照常留档（含模型本来想选的 {@code expert_agent_key}），便于复盘「用户指定 vs 模型意图」。</p>
     *
     * @param route 编排运行状态（{@code pinnedExpertId} 非空）
     * @param id    工具调用 ID
     * @param name  工具名
     * @param input 模型入参（留档用）
     * @return 工具结果
     */
    private Mono<ToolResultBlock> runPinned(AiRouteContext route, String id, String name,
                                            Map<String, Object> input) {
        route.action = AiRouteRun.ACTION_PINNED;
        route.decisionJson = JsonUtils.toJsonString(input);
        return Mono.fromCallable(() -> orchestrator.executePinned(route))
            .subscribeOn(Schedulers.boundedElastic())
            .map(report -> ToolResultBlock.text(report).withIdAndName(id, name))
            .onErrorResume(error -> {
                String message = AiSseSupport.rootCauseMessage(error);
                route.degraded = true;
                route.degradeReason = "编排异常：" + message;
                return Mono.just(ToolResultBlock
                    .error("专家调用失败：" + message
                        + "。请如实告知用户「未能拿到专家结论」，然后基于你自己掌握的信息回答，不要编造专家结论。")
                    .withIdAndName(id, name));
            });
    }

    /**
     * 构造参数 schema。
     *
     * @return 参数 schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
            "type", "string",
            "enum", List.of(AiRouteRun.ACTION_SELF, AiRouteRun.ACTION_ROUTE),
            "description", "self=你自己回答；route=把任务交给专家"));
        properties.put("expert_agent_key", Map.of(
            "type", "string",
            "description", "action=route 时必填：候选清单里的 key"));
        properties.put("task_context", Map.of(
            "type", "object",
            "description", "本任务片段，建议含 goal（目标）/ facts（已知事实）/ constraints（约束）/"
                + "expected（期望产出）。只写本任务必需的事实，不要把整段对话历史与无关个人信息搬进去"));
        properties.put("reason", Map.of(
            "type", "string",
            "description", "决策理由（仅留档，用于复盘路由质量）"));
        properties.put("confidence", Map.of(
            "type", "number",
            "minimum", 0,
            "maximum", 1,
            "description", "你对本次决策的置信度；不确定时倾向 self"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    /**
     * 安全转字符串。
     *
     * @param value 原始值
     * @return 字符串；为空时返回 null
     */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.isBlank(text) ? null : text;
    }

    /**
     * 把入参归一化为 JSON 文本（模型可能直接给对象，也可能给字符串）。
     *
     * @param value 原始值
     * @return JSON 文本；为空时返回 null
     */
    private static String jsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return StringUtils.isBlank(text) ? null : text;
        }
        return JsonUtils.toJsonString(value);
    }
}
