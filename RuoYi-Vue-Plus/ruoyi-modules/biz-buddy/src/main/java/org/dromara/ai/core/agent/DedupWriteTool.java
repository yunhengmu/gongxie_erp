package org.dromara.ai.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 写工具「短期去重」装饰器（REQ-AI-30 安全护栏）。
 *
 * <p>包在业务写工具外层（**只有专家装配的写工具会被包**，见 {@code AgentAssembler#registerTools}），
 * 执行前先查 {@link AiWriteDedupStore}：同一专家会话内「工具名 + 入参」相同的写调用若已成功执行过，
 * 则**不再执行**，直接回填「已跳过重复执行」提示，让模型复用上一次的结果继续完成结论。</p>
 *
 * <p><b>为什么在工具层拦</b>：契约校验与 HITL 续跑都在工具执行之后才发生，应用层无法回溯阻止
 * 已经发生的副作用；只有挂到工具真正执行之前才拦得住。装饰器保持原工具的 name / description /
 * inputSchema / {@code readOnly=false}，对模型与权限引擎完全透明。</p>
 *
 * <p><b>只拦成功后的重复</b>：首次调用若返回 ERROR（业务校验不过、权限拒绝等）不登记，
 * 模型修正入参后仍可重试。</p>
 *
 * @author BizBuddy
 */
final class DedupWriteTool extends ToolBase {

    /**
     * 被装饰的真实工具
     */
    private final AgentTool delegate;

    /**
     * 去重登记表
     */
    private final AiWriteDedupStore dedupStore;

    /**
     * @param delegate   真实写工具（业务工具实现）
     * @param dedupStore 去重登记表
     */
    DedupWriteTool(AgentTool delegate, AiWriteDedupStore dedupStore) {
        super(ToolBase.builder()
            .name(delegate.getName())
            .description(delegate.getDescription())
            .inputSchema(delegate.getParameters())
            .readOnly(false)
            .concurrencySafe(false));
        this.delegate = delegate;
        this.dedupStore = dedupStore;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param == null ? null : param.getRuntimeContext();
        // 作用域取运行期 sessionId：专家链路下即 routeRunId#expertAgentId（一次编排一个）
        String scope = context == null ? null : context.getSessionId();
        Map<String, Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
        String toolName = getName();
        if (scope == null || !dedupStore.seen(scope, toolName, input)) {
            // 未执行过（或无法判定）：正常执行，仅在**非 ERROR** 结果后登记，失败允许重试
            return delegate.callAsync(param).doOnNext(result -> {
                if (result != null && result.getState() != ToolResultState.ERROR) {
                    dedupStore.remember(scope, toolName, input);
                }
            });
        }
        ToolUseBlock use = param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? toolName : use.getName();
        return Mono.just(ToolResultBlock.text(
                "该写操作在本次任务中已执行成功，已跳过重复执行（工具：" + toolName
                    + "）。请直接复用上一次调用的结果来给出结论，不要再次执行该操作。")
            .withIdAndName(id, name));
    }

}
