package org.dromara.ai.domain.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpClientAssembly} 单元测试：空结果与按 Server / 工具名的查询语义。
 */
@DisplayName("MCP 客户端解析结果")
class McpClientAssemblyTest {

    @Test
    @DisplayName("空装配结果各字段均为空")
    void shouldBuildEmptyAssembly() {
        McpClientAssembly assembly = McpClientAssembly.empty();

        assertTrue(assembly.serverNames().isEmpty());
        assertTrue(assembly.toolNames().isEmpty());
        assertTrue(assembly.aliases().isEmpty());
        assertTrue(assembly.toolDecisions().isEmpty());
        assertEquals("", assembly.signature());
    }

    @Test
    @DisplayName("aliasesOf 命中返回映射，未命中返回空 Map")
    void shouldResolveAliases() {
        McpClientAssembly assembly = new McpClientAssembly(
            List.of("srv1"), List.of("a_b"), Map.of("srv1", Map.of("a.b", "a_b")),
            Map.of("a_b", "0"), "sig");

        assertEquals(Map.of("a.b", "a_b"), assembly.aliasesOf("srv1"));
        assertTrue(assembly.aliasesOf("srv2").isEmpty());
    }

    @Test
    @DisplayName("decisionOf 命中返回决策，未命中返回 null")
    void shouldResolveDecision() {
        McpClientAssembly assembly = new McpClientAssembly(
            List.of("srv1"), List.of("a_b"), Map.of(),
            Map.of("a_b", "2"), "sig");

        assertEquals("2", assembly.decisionOf("a_b"));
        assertNull(assembly.decisionOf("other"));
    }

    @Test
    @DisplayName("构造后可读取全部字段")
    void shouldExposeFields() {
        McpClientAssembly assembly = new McpClientAssembly(
            List.of("srv1", "srv2"), List.of("t1", "t2"), Map.of(), Map.of("t1", "1"), "sig");

        assertEquals(List.of("srv1", "srv2"), assembly.serverNames());
        assertEquals(List.of("t1", "t2"), assembly.toolNames());
        assertEquals("sig", assembly.signature());
    }

}