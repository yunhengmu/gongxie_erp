package org.dromara.ai.core.agent;

import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentFingerprintCalculator} 单元测试：定义指纹与模型槽位指纹。
 */
@DisplayName("智能体装配指纹计算器")
class AgentFingerprintCalculatorTest {

    private final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
    private final AgentFingerprintCalculator calculator = new AgentFingerprintCalculator(mapper);

    private static AiExpertAgent definition() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        agent.setStatus("0");
        agent.setModelId(1L);
        agent.setSummaryModelId(2L);
        agent.setAgentCode("expert_a");
        return agent;
    }

    private static AiModelConfig config(Long id, String code) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setProvider("openai");
        config.setModelCode(code);
        config.setBaseUrl("https://api.example.com");
        config.setModelType("chat");
        config.setStatus("0");
        config.setTimeoutSeconds(30);
        config.setMaxRetries(2);
        config.setUpdateTime(LocalDateTime.of(2026, 1, 2, 0, 0));
        return config;
    }

    @Test
    @DisplayName("拼装完整定义指纹")
    void shouldBuildFingerprint() {
        AiTool tool = new AiTool();
        tool.setToolCode("t1");
        tool.setToolType("0");
        tool.setImplName("search_user");
        tool.setUpdateTime(LocalDateTime.of(2026, 1, 3, 0, 0));
        AiAgentTool bind = new AiAgentTool();
        bind.setToolCode("t1");
        bind.setDecision("1");
        AiSubagent subagent = new AiSubagent();
        subagent.setId(7L);
        subagent.setName("sub");
        subagent.setWorkspaceMode(AiSubagent.WORKSPACE_SHARED);
        subagent.setModelId(9L);
        subagent.setSteps(5);
        subagent.setToolCodes("[\"a\"]");
        subagent.setUpdateTime(LocalDateTime.of(2026, 1, 4, 0, 0));

        String fingerprint = calculator.buildFingerprint(definition(), List.of(tool), List.of(bind),
            "chat=1:x;", new AiCompactionConfigBo(), new AiPlanConfigBo(), new AiMemoryConfigBo(),
            new AiWorkspaceConfigBo(), List.of("skill_a"), McpClientAssembly.empty(), List.of(subagent), "pack-1");

        assertTrue(fingerprint.contains("expert_a"));
        assertTrue(fingerprint.contains("t1:0:search_user:"));
        assertTrue(fingerprint.contains("t1=1;"));
        assertTrue(fingerprint.contains("|skills=skill_a"));
        assertTrue(fingerprint.contains("|subagents="));
        assertTrue(fingerprint.contains("|mcp="));
        assertTrue(fingerprint.contains("|pack=pack-1"));
    }

    @Test
    @DisplayName("空集合时指纹仍可拼装")
    void shouldBuildFingerprintWithEmptyCollections() {
        String fingerprint = calculator.buildFingerprint(definition(), List.of(), List.of(),
            "", new AiCompactionConfigBo(), new AiPlanConfigBo(), new AiMemoryConfigBo(),
            new AiWorkspaceConfigBo(), List.of(), McpClientAssembly.empty(), List.of(), "");
        assertTrue(fingerprint.contains("|skills="));
        assertTrue(fingerprint.contains("|subagents=|"));
    }

    @Test
    @DisplayName("模型槽位指纹区分存在与缺失")
    void shouldBuildModelSignature() {
        AiExpertAgent definition = definition();
        definition.setMemoryModelId(3L);
        definition.setSubagentModelId(4L);
        when(mapper.selectById(1L)).thenReturn(config(1L, "gpt-4"));
        when(mapper.selectById(2L)).thenReturn(null);
        when(mapper.selectById(3L)).thenReturn(config(3L, "gpt-3"));
        when(mapper.selectById(4L)).thenReturn(config(4L, "gpt-5"));

        String signature = calculator.modelSignature(definition);
        assertTrue(signature.contains("chat=1:"));
        assertTrue(signature.contains("gpt-4"));
        assertTrue(signature.contains("summary=2:missing;"));
        assertTrue(signature.contains("memory=3:"));
        assertTrue(signature.contains("subagent=4:"));
    }

    @Test
    @DisplayName("无任何模型时模型指纹为空串")
    void shouldReturnEmptyModelSignatureWhenNoModels() {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setAgentCode("plain");
        assertEquals("", calculator.modelSignature(agent));
    }

    @Test
    @DisplayName("策略变化改变指纹")
    void shouldChangeFingerprintWhenPolicyChanges() {
        AiCompactionConfigBo first = new AiCompactionConfigBo();
        first.setEnabled(true);
        AiCompactionConfigBo second = new AiCompactionConfigBo();
        second.setEnabled(false);

        String a = calculator.buildFingerprint(definition(), List.of(), List.of(), "",
            first, new AiPlanConfigBo(), new AiMemoryConfigBo(), new AiWorkspaceConfigBo(),
            List.of(), McpClientAssembly.empty(), null, "");
        String b = calculator.buildFingerprint(definition(), List.of(), List.of(), "",
            second, new AiPlanConfigBo(), new AiMemoryConfigBo(), new AiWorkspaceConfigBo(),
            List.of(), McpClientAssembly.empty(), null, "");
        assertNotEquals(a, b);
        assertFalse(a.equals(b));
    }
}