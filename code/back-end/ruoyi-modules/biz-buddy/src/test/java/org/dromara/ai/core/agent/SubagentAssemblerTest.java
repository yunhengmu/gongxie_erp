package org.dromara.ai.core.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubagentAssembler} 单元测试：子智能体声明生成与工具白名单解析。
 */
@DisplayName("子智能体装配支撑")
class SubagentAssemblerTest {

    private final SubagentAssembler assembler = new SubagentAssembler();

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    private static AiSubagent subagent(String name, String workspaceMode, String toolCodes) {
        AiSubagent subagent = new AiSubagent();
        subagent.setId(1L);
        subagent.setName(name);
        subagent.setDescription("用途说明");
        subagent.setWorkspaceMode(workspaceMode);
        subagent.setToolCodes(toolCodes);
        return subagent;
    }

    private static AiExpertAgent definition(Long subagentModelId) {
        AiExpertAgent def = new AiExpertAgent();
        def.setAgentCode("host");
        def.setSubagentModelId(subagentModelId);
        return def;
    }

    @Test
    @DisplayName("生成共享工作区声明并继承默认步数与工具池")
    void shouldBuildSharedDeclaration() {
        AiSubagent sub = subagent("s1", AiSubagent.WORKSPACE_SHARED, null);
        List<SubagentDeclaration> decls = assembler.buildSubagentDeclarations(
            List.of(sub), definition(null), List.of("read_a", "read_b"));
        assertEquals(1, decls.size());
        SubagentDeclaration decl = decls.get(0);
        assertEquals("s1", decl.getName());
        assertEquals(WorkspaceMode.SHARED, decl.getWorkspaceMode());
        assertEquals(AiSubagent.DEFAULT_STEPS, decl.getSteps());
        assertEquals(List.of("read_a", "read_b"), decl.getTools());
        assertEquals(null, decl.getModel());
    }

    @Test
    @DisplayName("隔离工作区与内联提示词")
    void shouldBuildIsolatedDeclarationWithInlinePrompt() {
        AiSubagent sub = subagent("s2", AiSubagent.WORKSPACE_ISOLATED, "[\"read_a\"]");
        sub.setSysPrompt("你是助手");
        sub.setSteps(3);
        sub.setModelId(9L);
        List<SubagentDeclaration> decls = assembler.buildSubagentDeclarations(
            List.of(sub), definition(7L), List.of("read_a", "read_b"));
        SubagentDeclaration decl = decls.get(0);
        assertEquals(WorkspaceMode.ISOLATED, decl.getWorkspaceMode());
        assertEquals(3, decl.getSteps());
        assertEquals("你是助手", decl.getInlineAgentsBody());
        assertEquals(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX + 9L, decl.getModel());
    }

    @Test
    @DisplayName("白名单仅保留工具池中存在的编码")
    void shouldFilterRequestedToolsByPool() {
        AiSubagent sub = subagent("s3", AiSubagent.WORKSPACE_SHARED, "[\"read_a\",\"write_x\"]");
        List<SubagentDeclaration> decls = assembler.buildSubagentDeclarations(
            List.of(sub), definition(7L), List.of("read_a", "read_b"));
        assertEquals(List.of("read_a"), decls.get(0).getTools());
        assertEquals(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX + 7L, decls.get(0).getModel());
    }

    @Test
    @DisplayName("无可用工具时使用占位名实现零下放")
    void shouldUsePlaceholderWhenNoTools() {
        AiSubagent sub = subagent("s4", AiSubagent.WORKSPACE_SHARED, "[\"write_x\"]");
        List<SubagentDeclaration> decls = assembler.buildSubagentDeclarations(
            List.of(sub), definition(null), List.of("read_a"));
        assertEquals(List.of(AgentRegistry.SUBAGENT_TOOL_NONE), decls.get(0).getTools());
    }

    @Test
    @DisplayName("非法白名单 JSON 按留空处理")
    void shouldTreatInvalidJsonAsEmpty() {
        AiSubagent sub = subagent("s5", AiSubagent.WORKSPACE_SHARED, "not-json");
        List<SubagentDeclaration> decls = assembler.buildSubagentDeclarations(
            List.of(sub), definition(null), List.of("read_a"));
        assertEquals(List.of("read_a"), decls.get(0).getTools());
        assertTrue(decls.get(0).getInlineAgentsBody() == null);
    }
}