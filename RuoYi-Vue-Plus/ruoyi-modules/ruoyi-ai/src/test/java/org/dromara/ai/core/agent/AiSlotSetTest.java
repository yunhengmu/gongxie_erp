package org.dromara.ai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiSlotSet} 单元测试：覆盖式合并、空判定与两种渲染。
 */
@DisplayName("继承槽位集合")
class AiSlotSetTest {

    @Test
    @DisplayName("空槽位判定")
    void shouldDetectEmptySlots() {
        assertTrue(AiSlotSet.empty().isEmpty());
        assertFalse(new AiSlotSet("Asia/Shanghai", null, null, null).isEmpty());
        assertFalse(new AiSlotSet(null, null, "zh-CN", null).isEmpty());
    }

    @Test
    @DisplayName("覆盖方为空时原样返回")
    void shouldReturnThisWhenOverrideIsNull() {
        AiSlotSet base = new AiSlotSet("Asia/Shanghai", null, "zh-CN", null);
        assertSame(base, base.overlay(null));
    }

    @Test
    @DisplayName("仅非空字段覆盖")
    void shouldOverlayOnlyNonNullFields() {
        AiSlotSet base = new AiSlotSet("Asia/Shanghai", "近 7 天", "zh-CN", null);
        AiSlotSet merged = base.overlay(new AiSlotSet(null, "近 30 天", null, "任务线A"));
        assertEquals("Asia/Shanghai", merged.timezone());
        assertEquals("近 30 天", merged.timeWindow());
        assertEquals("zh-CN", merged.locale());
        assertEquals("任务线A", merged.taskLine());

        AiSlotSet blank = base.overlay(new AiSlotSet(" ", " ", " ", " "));
        assertEquals("Asia/Shanghai", blank.timezone());
        assertEquals("近 7 天", blank.timeWindow());
    }

    @Test
    @DisplayName("渲染为空槽位返回占位说明")
    void shouldRenderPlaceholderWhenEmpty() {
        assertEquals("（本轮无继承槽位）", AiSlotSet.empty().render());
        assertEquals("", AiSlotSet.empty().renderInline());
    }

    @Test
    @DisplayName("渲染多行与内联文本")
    void shouldRenderSlots() {
        AiSlotSet slots = new AiSlotSet("Asia/Shanghai", "近 7 天", "zh-CN", "任务线A");
        String rendered = slots.render();
        assertTrue(rendered.contains("- 时区：Asia/Shanghai"));
        assertTrue(rendered.contains("- 时间窗：近 7 天"));
        assertTrue(rendered.contains("- 语言：zh-CN"));
        assertTrue(rendered.contains("- 任务线：任务线A"));
        assertFalse(rendered.endsWith("\n"));

        assertEquals("时区=Asia/Shanghai；时间窗=近 7 天；语言=zh-CN；任务线=任务线A", slots.renderInline());
        assertEquals("时区=Asia/Shanghai", new AiSlotSet("Asia/Shanghai", null, null, null).renderInline());
    }
}