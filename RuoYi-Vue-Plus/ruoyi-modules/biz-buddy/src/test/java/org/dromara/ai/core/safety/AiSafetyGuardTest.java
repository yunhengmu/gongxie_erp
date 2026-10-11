package org.dromara.ai.core.safety;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiSafetyGuard} 单元测试：三种命中动作与 PII 脱敏的组合口径。
 */
@DisplayName("安全合规统一守卫")
class AiSafetyGuardTest {

    private final AiRuntimeConfigStore store = mock(AiRuntimeConfigStore.class);

    private final IAiContentSafetyEngine engine = mock(IAiContentSafetyEngine.class);

    /**
     * 构造守卫并打桩配置与引擎。
     */
    private AiSafetyGuard guard(AiSecurityConfigBo cfg, boolean engineHit) {
        when(store.security()).thenReturn(cfg);
        when(engine.engineName()).thenReturn("local");
        when(engine.check(any(), any())).thenReturn(engineHit
            ? new ContentSafetyResult(true, List.of("违规词"), List.of("违禁"))
            : ContentSafetyResult.miss());
        return new AiSafetyGuard(store, engine);
    }

    private AiSecurityConfigBo config(boolean enabled, String action, boolean maskPii) {
        AiSecurityConfigBo cfg = new AiSecurityConfigBo();
        cfg.setEnabled(enabled);
        cfg.setAction(action);
        cfg.setMaskPii(maskPii);
        return cfg;
    }

    @Test
    @DisplayName("全部关闭时原样放行")
    void shouldPassThroughWhenDisabled() {
        AiSafetyGuard guard = guard(config(false, "block", false), true);

        SafetyDecision decision = guard.guard("违规词", SafetyScene.INPUT);

        assertFalse(decision.hit());
        assertEquals("违规词", decision.text());
    }

    @Test
    @DisplayName("action=block 命中即阻断")
    void shouldBlockOnHit() {
        AiSafetyGuard guard = guard(config(true, "block", false), true);

        SafetyDecision decision = guard.guard("包含违规词的句子", SafetyScene.INPUT);

        assertTrue(decision.blocked());
    }

    @Test
    @DisplayName("action=mask 命中词被替换为掩码")
    void shouldMaskHitWord() {
        AiSafetyGuard guard = guard(config(true, "mask", false), true);

        SafetyDecision decision = guard.guard("包含违规词的句子", SafetyScene.OUTPUT);

        assertFalse(decision.blocked());
        assertTrue(decision.text().contains("***"));
        assertFalse(decision.text().contains("违规词"));
    }

    @Test
    @DisplayName("action=log 只留痕、原文放行")
    void shouldLogOnly() {
        AiSafetyGuard guard = guard(config(true, "log", false), true);

        SafetyDecision decision = guard.guard("包含违规词的句子", SafetyScene.OUTPUT);

        assertTrue(decision.hit());
        assertEquals("包含违规词的句子", decision.text());
    }

    @Test
    @DisplayName("PII 脱敏独立生效（输出场景）")
    void shouldMaskPiiOnOutput() {
        AiSafetyGuard guard = guard(config(false, "log", true), false);

        SafetyDecision decision = guard.guard("联系电话 13812345678", SafetyScene.OUTPUT);

        assertTrue(decision.hit());
        assertTrue(decision.text().contains("138****5678"));
    }

    @Test
    @DisplayName("输入场景不做 PII 脱敏（避免影响用户原话）")
    void shouldNotMaskPiiOnInput() {
        AiSafetyGuard guard = guard(config(false, "log", true), false);

        SafetyDecision decision = guard.guard("联系电话 13812345678", SafetyScene.INPUT);

        assertFalse(decision.hit());
        assertEquals("联系电话 13812345678", decision.text());
    }

}