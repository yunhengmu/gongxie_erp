package org.dromara.ai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiRunRegistry} 的纯单元测试：登记 / 注销 / 取消的语义。
 *
 * @author BizBuddy
 */
@DisplayName("单次执行取消注册表")
class AiRunRegistryTest {

    private final AiRunRegistry registry = new AiRunRegistry();

    @Test
    @DisplayName("登记后可取消，并返回句柄结果")
    void shouldCancelRegistered() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AiRunRegistry.Cancelable handle = () -> {
            cancelled.set(true);
            return true;
        };
        registry.register(1L, handle);

        assertTrue(registry.cancel(1L));
        assertTrue(cancelled.get());
    }

    @Test
    @DisplayName("注销后取消不再命中")
    void shouldNotCancelAfterUnregister() {
        AiRunRegistry.Cancelable handle = () -> true;
        registry.register(1L, handle);
        registry.unregister(1L, handle);

        assertFalse(registry.cancel(1L));
    }

    @Test
    @DisplayName("无登记时取消返回 false")
    void shouldReturnFalseWhenAbsent() {
        assertFalse(registry.cancel(9L));
    }

    @Test
    @DisplayName("仅注销同一句柄：旧句柄的注销不影响新登记的句柄")
    void shouldOnlyRemoveSameHandle() {
        AiRunRegistry.Cancelable oldHandle = () -> true;
        AiRunRegistry.Cancelable newHandle = () -> true;
        registry.register(2L, oldHandle);
        registry.register(2L, newHandle);
        registry.unregister(2L, oldHandle);

        assertTrue(registry.cancel(2L));
    }
}