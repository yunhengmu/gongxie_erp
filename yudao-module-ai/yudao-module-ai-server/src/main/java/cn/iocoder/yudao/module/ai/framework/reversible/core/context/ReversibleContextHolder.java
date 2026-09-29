package cn.iocoder.yudao.module.ai.framework.reversible.core.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * @author hengmu
 */
public class ReversibleContextHolder {

    private static final ThreadLocal<Snapshot> CONTEXT = new TransmittableThreadLocal<>();

    public static Snapshot get() {
        return CONTEXT.get();
    }

    public static void set(Long messageId, Long conversationId, Long tenantId) {
        CONTEXT.set(new Snapshot(messageId, conversationId, tenantId));
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record Snapshot(Long messageId, Long conversationId, Long tenantId) {
    }

}
