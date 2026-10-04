package org.dromara.ai.core.agent;

import io.agentscope.core.permission.PermissionMode;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSnapshotFactory} 单元测试：装配快照的模型版本与上下文清单。
 */
@DisplayName("智能体装配快照工厂")
class AgentSnapshotFactoryTest {

    private final AiPermissionModeStore permissionModeStore = mock(AiPermissionModeStore.class);
    private final AgentModelFactory modelFactory = mock(AgentModelFactory.class);
    private final AgentSnapshotFactory factory = new AgentSnapshotFactory(permissionModeStore, modelFactory);

    private static AiExpertAgent definition() {
        AiExpertAgent def = new AiExpertAgent();
        def.setModelId(1L);
        def.setSummaryModelId(2L);
        def.setMemoryModelId(3L);
        def.setSubagentModelId(4L);
        return def;
    }

    private static AiModelConfig modelConfig() {
        AiModelConfig config = new AiModelConfig();
        config.setModelCode("gpt-4");
        config.setProvider("openai");
        config.setBaseUrl("https://api.example.com");
        config.setTimeoutSeconds(30);
        config.setMaxRetries(2);
        return config;
    }

    @Test
    @DisplayName("构造快照填充模型版本与上下文清单")
    void shouldBuildSnapshot() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DEFAULT);
        when(modelFactory.slotModelCode(2L)).thenReturn("summary-code");
        when(modelFactory.slotModelCode(3L)).thenReturn("memory-code");
        when(modelFactory.slotModelCode(4L)).thenReturn("sub-code");

        AiSubagent subagent = new AiSubagent();
        subagent.setName("sub-1");
        AiAgentTool bind = new AiAgentTool();
        bind.setToolCode("t1");
        bind.setDecision("0");

        AiCompactionConfigBo compaction = new AiCompactionConfigBo();
        compaction.setEnabled(true);
        AiPlanConfigBo plan = new AiPlanConfigBo();
        plan.setPlanFileDirectory("plans");
        AiMemoryConfigBo memory = new AiMemoryConfigBo();
        memory.setFlushMode("always");
        AiWorkspaceConfigBo workspace = new AiWorkspaceConfigBo();

        AgentSnapshot snapshot = factory.buildSnapshot(definition(), modelConfig(),
            List.of("t1"), List.of(100L), List.of("skill_a"), List.of(subagent), List.of(bind),
            compaction, plan, memory, workspace, McpClientAssembly.empty(), 8);

        assertEquals(1L, snapshot.modelId());
        assertEquals("gpt-4", snapshot.modelCode());
        assertEquals("openai", snapshot.modelProvider());
        assertEquals("summary-code", snapshot.summaryModelCode());
        assertEquals("memory-code", snapshot.memoryModelCode());
        assertEquals("sub-code", snapshot.subagentModelCode());

        Map<String, Object> context = snapshot.context();
        assertEquals(List.of("t1"), context.get("tools"));
        assertEquals(List.of(100L), context.get("kbIds"));
        assertEquals(List.of("skill_a"), context.get("skills"));
        assertEquals(List.of("sub-1"), context.get("subagents"));
        assertEquals("DEFAULT", context.get("permissionMode"));
        assertEquals(8, context.get("maxIters"));
        assertTrue(context.containsKey("model"));
        assertTrue(context.containsKey("compaction"));
        assertTrue(context.containsKey("plan"));
        assertTrue(context.containsKey("memory"));
        assertTrue(context.containsKey("workspace"));
        assertNotNull(context.get("toolDecisions"));

        @SuppressWarnings("unchecked")
        Map<String, String> decisions = (Map<String, String>) context.get("toolDecisions");
        assertEquals("0", decisions.get("t1"));
    }

    @Test
    @DisplayName("可选槽位模型为空时快照对应字段为空")
    void shouldLeaveSlotCodesNull() {
        when(permissionModeStore.resolve()).thenReturn(PermissionMode.DONT_ASK);
        when(modelFactory.slotModelCode(2L)).thenReturn(null);
        when(modelFactory.slotModelCode(3L)).thenReturn(null);
        when(modelFactory.slotModelCode(4L)).thenReturn(null);

        AgentSnapshot snapshot = factory.buildSnapshot(definition(), modelConfig(),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new AiCompactionConfigBo(), new AiPlanConfigBo(), new AiMemoryConfigBo(),
            new AiWorkspaceConfigBo(), McpClientAssembly.empty(), 1);
        assertNull(snapshot.summaryModelCode());
        assertEquals("DONT_ASK", snapshot.context().get("permissionMode"));
    }
}