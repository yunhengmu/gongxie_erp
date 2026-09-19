package cn.iocoder.yudao.module.ai.framework.reversible.core.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 可撤回操作的 AI 上下文 Holder
 *
 * <p>用途：把「撤回单元（messageId）」从 AI 工具层（对接层）传递到业务层，
 * 使 {@link cn.iocoder.yudao.module.ai.framework.reversible.core.annotation.Reversible @Reversible}
 * 可以标注在<b>业务方法</b>上，而不是被迫标注在工具方法上。
 *
 * <p><b>为什么不能让业务方法直接收 ToolContext</b>：ToolContext 是 Spring AI 的类型。
 * 一旦业务方法声明它，业务层就被 AI 概念污染；且该方法被 REST 接口、定时任务调用时，
 * 根本没有 ToolContext 可传。
 *
 * <p><b>为什么 ThreadLocal 在这里可靠</b>：写入点是「工具方法入口」—— 那里既拿得到 ToolContext、
 * 又已经处于工具执行线程上；业务方法是被<b>同线程</b>的普通 Java 方法调用触发的，不存在跨线程。
 * （此前「工具在 Flux 线程执行、ThreadLocal 不继承」的顾虑，针对的是「请求线程 → 工具线程」
 * 这一跳，与本场景不同。）
 *
 * <p>选用 {@link TransmittableThreadLocal} 是为了业务方法内部再起线程池时仍能传递。
 *
 * <p>写入方：{@code ReversibleBridgeAspect}（拦截带 ToolContext 参数的方法）。
 * 读取方：{@code ReversibleLedgerAspect}（入账切面）。
 *
 * @author yudao
 */
public class ReversibleContextHolder {

    /**
     * 当前 AI 工具调用上下文
     *
     * 为 null 表示「本次调用不是 AI 触发的」，对应 REST 接口、定时任务等入口。
     */
    private static final ThreadLocal<Snapshot> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 获得当前上下文
     *
     * @return 上下文；非 AI 触发的调用返回 null
     */
    public static Snapshot get() {
        return CONTEXT.get();
    }

    /**
     * 设置当前上下文
     *
     * @param messageId      撤回单元：用户消息编号，关联 {@code ai_chat_message.id}
     * @param conversationId 对话编号
     * @param tenantId       租户编号
     */
    public static void set(Long messageId, Long conversationId, Long tenantId) {
        CONTEXT.set(new Snapshot(messageId, conversationId, tenantId));
    }

    /**
     * 清理上下文
     *
     * 必须在调用结束后清理：工具可能在线程池线程上执行，不清理会造成上下文串用。
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 一次 AI 工具调用携带的上下文快照
     *
     * @param messageId      撤回单元：用户消息编号
     * @param conversationId 对话编号
     * @param tenantId       租户编号
     */
    public record Snapshot(Long messageId, Long conversationId, Long tenantId) {
    }

}
