package org.dromara.ai.domain.dto;

import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiSessionResourceSet} 单元测试：空集语义、签名确定性与三类资源的合并优先级。
 */
@DisplayName("会话级资源集")
class AiSessionResourceSetTest {

    @Test
    @DisplayName("空资源集：空签名 + 空合并结果")
    void shouldProvideEmptySet() {
        AiSessionResourceSet empty = AiSessionResourceSet.empty();

        assertEquals(AiSessionResourceSet.signature(List.of()), empty.signature());
        assertEquals("", empty.packSignature());
        assertTrue(empty.mergedToolDecisions().isEmpty());
        assertTrue(empty.mergedSkillIds().isEmpty());
        assertTrue(empty.mergedMcpSelections().isEmpty());
        assertNull(empty.sessionId());
    }

    @Test
    @DisplayName("身份签名与顺序无关，且固定 16 位十六进制")
    void shouldComputeOrderInsensitiveSignature() {
        String a = AiSessionResourceSet.signature(List.of("tool:b", "tool:a"));
        String b = AiSessionResourceSet.signature(List.of("tool:a", "tool:b"));

        assertEquals(a, b);
        assertEquals(16, a.length());
        assertTrue(a.matches("[0-9a-f]{16}"));
        assertNotEquals(a, AiSessionResourceSet.signature(List.of("tool:a")));
    }

    @Test
    @DisplayName("工具决策合并：直接选中覆盖包内")
    void shouldMergeToolDecisionsWithDirectWinning() {
        AiSessionResourceSet set = new AiSessionResourceSet("sig", "pack",
            Map.of("a", "0", "b", "1"), List.of(), List.of(),
            Map.of("b", "2", "c", "0"), List.of(), List.of(), 7L);

        Map<String, String> merged = set.mergedToolDecisions();
        assertEquals("0", merged.get("a"));
        assertEquals("2", merged.get("b"));
        assertEquals("0", merged.get("c"));
    }

    @Test
    @DisplayName("技能合并：包内 ∪ 直接选中，去重保序")
    void shouldMergeSkillIdsDedupKeepOrder() {
        AiSessionResourceSet set = new AiSessionResourceSet("sig", "pack",
            Map.of(), List.of(1L, 2L), List.of(),
            Map.of(), List.of(2L, 3L), List.of(), 7L);

        assertEquals(List.of(1L, 2L, 3L), set.mergedSkillIds());
    }

    @Test
    @DisplayName("MCP 选择合并：包内在前、直接选中在后")
    void shouldMergeMcpSelections() {
        AiScenarioPackMcpTool packTool = new AiScenarioPackMcpTool();
        packTool.setId(1L);
        AiScenarioPackMcpTool directTool = new AiScenarioPackMcpTool();
        directTool.setId(2L);

        AiSessionResourceSet set = new AiSessionResourceSet("sig", "pack",
            Map.of(), List.of(), List.of(packTool),
            Map.of(), List.of(), List.of(directTool), 7L);

        List<AiScenarioPackMcpTool> merged = set.mergedMcpSelections();
        assertEquals(2, merged.size());
        assertEquals(1L, merged.get(0).getId());
        assertEquals(2L, merged.get(1).getId());
    }

}