package org.dromara.ai.core.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.dromara.common.core.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiToolSupport} 单元测试：操作人解析、权限判定、文案映射与结果封装。
 */
@DisplayName("AI 工具共用支撑")
class AiToolSupportTest {

    @Test
    @DisplayName("从运行时上下文解析操作人")
    void shouldResolveOperatorId() {
        ToolCallParam param = ToolCallParam.builder()
            .runtimeContext(RuntimeContext.builder().userId("42").build())
            .build();
        assertEquals(42L, AiToolSupport.operatorId(param));
    }

    @Test
    @DisplayName("操作人缺失或非法时返回空")
    void shouldReturnNullOperatorIdWhenMissing() {
        assertNull(AiToolSupport.operatorId(null));
        assertNull(AiToolSupport.operatorId(ToolCallParam.builder().build()));
        ToolCallParam bad = ToolCallParam.builder()
            .runtimeContext(RuntimeContext.builder().userId("abc").build())
            .build();
        assertNull(AiToolSupport.operatorId(bad));
    }

    @Test
    @DisplayName("取工具入参，空时返回空 Map")
    void shouldReturnInputOrDefaultEmpty() {
        assertTrue(AiToolSupport.input(null).isEmpty());
        assertTrue(AiToolSupport.input(ToolCallParam.builder().build()).isEmpty());
        assertEquals(Map.of("a", 1), AiToolSupport.input(
            ToolCallParam.builder().input(Map.of("a", 1)).build()));
    }

    @Test
    @DisplayName("校验菜单权限含超级管理员通配")
    void shouldCheckMenuPermission() {
        PermissionService service = mock(PermissionService.class);
        when(service.getMenuPermission(1L)).thenReturn(Set.of("system:user:list"));
        assertTrue(AiToolSupport.hasPermission(service, 1L, "system:user:list"));
        assertTrue(!AiToolSupport.hasPermission(service, 1L, "system:user:add"));
        when(service.getMenuPermission(2L)).thenReturn(Set.of("*:*:*"));
        assertTrue(AiToolSupport.hasPermission(service, 2L, "anything"));
    }

    @Test
    @DisplayName("权限集合为空时一律无权限")
    void shouldDenyWhenNoPermissions() {
        PermissionService service = mock(PermissionService.class);
        when(service.getMenuPermission(1L)).thenReturn(null);
        when(service.getMenuPermission(2L)).thenReturn(Set.of());
        assertTrue(!AiToolSupport.hasPermission(service, 1L, "x"));
        assertTrue(!AiToolSupport.hasPermission(service, 2L, "x"));
    }

    @Test
    @DisplayName("解析数字 ID 与字符串取值")
    void shouldParseIdAndStringValue() {
        assertEquals(7L, AiToolSupport.parseId(" 7 "));
        assertEquals(7L, AiToolSupport.parseId(7));
        assertNull(AiToolSupport.parseId(null));
        assertNull(AiToolSupport.parseId(" "));
        assertNull(AiToolSupport.parseId("abc"));
        assertEquals("", AiToolSupport.stringValue(null));
        assertEquals("9", AiToolSupport.stringValue(9));
    }

    @Test
    @DisplayName("展示文案空值回落占位")
    void shouldRenderDisplayText() {
        assertEquals("hello", AiToolSupport.display(" hello "));
        assertEquals("（未设置）", AiToolSupport.display(" "));
        assertEquals("（未设置）", AiToolSupport.display(null));
    }

    @Test
    @DisplayName("状态与类型文案映射")
    void shouldMapStatusAndTypeText() {
        assertEquals("正常", AiToolSupport.statusText("0"));
        assertEquals("停用", AiToolSupport.statusText("1"));
        assertEquals("x", AiToolSupport.statusText("x"));
        assertEquals("男", AiToolSupport.genderText("0"));
        assertEquals("女", AiToolSupport.genderText("1"));
        assertEquals("未知", AiToolSupport.genderText("2"));
        assertEquals("其他", AiToolSupport.genderText("其他"));
        assertEquals("通知", AiToolSupport.noticeTypeText("1"));
        assertEquals("公告", AiToolSupport.noticeTypeText("2"));
        assertEquals("正常", AiToolSupport.noticeStatusText("0"));
        assertEquals("关闭", AiToolSupport.noticeStatusText("1"));
    }

    @Test
    @DisplayName("解析 ID 列表支持集合与逗号串")
    void shouldParseIdList() {
        assertNull(AiToolSupport.parseIds(null));
        assertArrayEquals(new Long[]{1L, 2L}, AiToolSupport.parseIds("1,2"));
        assertArrayEquals(new Long[]{1L, 2L}, AiToolSupport.parseIds(List.of(1, "2")));
        assertArrayEquals(new Long[]{}, AiToolSupport.parseIds(""));
        assertArrayEquals(new Long[]{3L}, AiToolSupport.parseIds("3,3"));
    }

    @Test
    @DisplayName("校验 ID 列表合法性")
    void shouldValidateIdList() {
        assertNull(AiToolSupport.validateIds(null));
        assertNull(AiToolSupport.validateIds("1,2"));
        assertNotNull(AiToolSupport.validateIds("1,a"));
        assertNotNull(AiToolSupport.validateIds(List.of("x")));
    }

    @Test
    @DisplayName("成功与失败结果封装")
    void shouldWrapToolResult() {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name("search").build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).build();
        ToolResultBlock ok = AiToolSupport.ok(param, "fallback", "done").block();
        assertNotNull(ok);
        assertEquals("call-1", ok.getId());
        assertEquals("search", ok.getName());
        assertEquals(ToolResultState.RUNNING, ok.getState());

        ToolResultBlock fail = AiToolSupport.fail(param, "fallback", " ").block();
        assertNotNull(fail);
        assertEquals(ToolResultState.ERROR, fail.getState());

        ToolResultBlock fallback = AiToolSupport.ok(ToolCallParam.builder().build(), "fallback", null).block();
        assertNotNull(fallback);
        assertEquals("fallback", fallback.getName());
    }
}