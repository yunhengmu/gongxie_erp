package org.dromara.ai.core.agent;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果「模型侧」安全守卫装饰器（安全合规 P3 ③ 补口）。
 *
 * <p>此前 `TOOL_RESULT` 守卫只作用于**落库副本**（`AiTurnFinisher#buildToolLogs`）与**下发前端副本**
 * （`AiStreamEventBridge#onToolResultEnd`），而**喂给模型 / 专家上下文的是原文**——专家的回答因此
 * 可能把手机号 / 邮箱等 PII 原样抄出来（实测 2026-10-13：工具返回的明文被抄进回答）。
 * 本装饰器把守卫前移到工具出口：**结果块在交给框架回填之前**即完成脱敏。</p>
 *
 * <p><b>为什么是装饰器而不是改各工具实现</b>：业务工具有二十多个（`AiToolFactory` 逐个构造），
 * 逐个改会重复且易漏；装饰器在 {@code AgentAssembler#registerTools} 统一包装，新增工具自动生效。
 * 与 {@link DedupWriteTool} 同款做法，且**完整保留**原工具的 name / description / inputSchema /
 * {@code readOnly} / {@code returnDirect} / {@code concurrencySafe}——对模型与权限引擎透明
 * （业务工具均未覆盖 {@code checkPermissions} 等权限方法，故包装不改变授权判定）。</p>
 *
 * <p><b>处置口径</b>：沿用「只脱敏不阻断」——命中内容审核的 `MASK` 与 PII 一律以脱敏文本回填；
 * 唯一例外是内容审核配置为 `BLOCK`：此时既不回填原文、也不回填脱敏文，而是替换为统一拒答文案，
 * 避免把命中词送给模型。**失败结果（ERROR）与 HITL 暂停结果（suspended）原样放行**：前者是业务校验
 * 提示、后者带 `agentscope_suspended` 元数据，改写会破坏暂停语义与恢复段。</p>
 *
 * <p><b>开关</b>：复用现有 `toolResultMask`（`AiSecurityConfigBo#isToolResultMask`，默认开）——
 * 关掉即「工具结果完全不过审」，与既有口径一致，不新增配置键。</p>
 *
 * @author BizBuddy
 */
final class ToolResultGuardTool extends ToolBase {

    /**
     * 被装饰的真实工具
     */
    private final AgentTool delegate;

    /**
     * 安全守卫（内容审核 + PII 脱敏）
     */
    private final AiSafetyGuard safetyGuard;

    /**
     * @param delegate    真实工具（业务工具实现）
     * @param safetyGuard 安全守卫
     */
    ToolResultGuardTool(AgentTool delegate, AiSafetyGuard safetyGuard) {
        super(ToolBase.builder()
            .name(delegate.getName())
            .description(delegate.getDescription())
            .inputSchema(delegate.getParameters())
            .readOnly(delegate.isReadOnly())
            .returnDirect(delegate.isReturnDirect())
            .concurrencySafe(delegate instanceof ToolBase base && base.isConcurrencySafe()));
        this.delegate = delegate;
        this.safetyGuard = safetyGuard;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        if (!safetyGuard.anyActive()) {
            // 快速路径：内容审核与 PII 脱敏都没开时不做任何改写
            return delegate.callAsync(param);
        }
        return delegate.callAsync(param).map(this::guard);
    }

    /**
     * 改写结果块：逐块脱敏文本内容。
     *
     * @param result 原结果块
     * @return 脱敏后的结果块（无变化时返回原对象）
     */
    private ToolResultBlock guard(ToolResultBlock result) {
        if (result == null || result.isSuspended() || result.getState() == ToolResultState.ERROR) {
            // 暂停结果带 agentscope_suspended 元数据、失败结果是业务校验文案：改写都会破坏原有语义
            return result;
        }
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return result;
        }
        boolean changed = false;
        List<ContentBlock> guarded = new ArrayList<>(output.size());
        for (ContentBlock block : output) {
            ContentBlock next = guardBlock(block);
            changed |= next != block;
            guarded.add(next);
        }
        if (!changed) {
            return result;
        }
        // 用 builder 保留 id / name / metadata / state（尤其 suspended 相关的元数据不能被丢）
        return ToolResultBlock.builder()
            .id(result.getId())
            .name(result.getName())
            .output(guarded)
            .metadata(result.getMetadata())
            .state(result.getState())
            .build();
    }

    /**
     * 单块脱敏：只处理文本块，其它内容块（图片等）原样返回。
     *
     * @param block 内容块
     * @return 脱敏后的内容块（无变化时返回原对象）
     */
    private ContentBlock guardBlock(ContentBlock block) {
        if (!(block instanceof TextBlock textBlock)) {
            return block;
        }
        String text = textBlock.getText();
        if (StringUtils.isEmpty(text)) {
            return block;
        }
        SafetyDecision decision = safetyGuard.guard(text, SafetyScene.TOOL_RESULT);
        if (!decision.hit()) {
            return block;
        }
        String guarded = decision.blocked() ? AiSafetyGuard.REFUSAL_TEXT : decision.text();
        return TextBlock.builder().text(guarded).build();
    }

}
