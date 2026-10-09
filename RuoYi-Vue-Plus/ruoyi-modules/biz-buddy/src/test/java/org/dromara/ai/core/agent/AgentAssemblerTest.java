package org.dromara.ai.core.agent;

import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentAssembler} 单元测试：从注册表抽出后可独立断言的纯函数
 * （系统提示词纪律、只读工具池、工具白名单、记忆抽取触发策略）。
 *
 * <p>完整的 {@code assemble} 会构造真实 {@link io.agentscope.harness.agent.HarnessAgent}
 * 并做文件系统 / 框架装配，其主体行为已由 {@link AgentRegistryTest} 从缓存与驱动侧覆盖，
 * 故本类聚焦无副作用的入参变换。</p>
 */
@DisplayName("智能体装配器：纯函数")
class AgentAssemblerTest {

    private static final String DISCIPLINE_MARK = "【技能优先】";

    private static AiTool tool(String code, String type) {
        AiTool tool = new AiTool();
        tool.setToolCode(code);
        tool.setToolType(type);
        return tool;
    }

    @Test
    @DisplayName("系统提示词：专家装配且带技能时追加技能优先纪律")
    void shouldAppendDisciplineForExpertWithSkills() {
        AiExpertAgent def = new AiExpertAgent();
        def.setSysPrompt("你是专家");

        String prompt = AgentAssembler.resolveSysPrompt(def, List.of("skill_a"), true);

        assertTrue(prompt.startsWith("你是专家"));
        assertTrue(prompt.contains(DISCIPLINE_MARK));
    }

    @Test
    @DisplayName("系统提示词：非专家或未装配技能时原样返回")
    void shouldReturnBasePromptWithoutDiscipline() {
        AiExpertAgent def = new AiExpertAgent();
        def.setSysPrompt("你是专家");

        assertEquals("你是专家", AgentAssembler.resolveSysPrompt(def, List.of("skill_a"), false));
        assertEquals("你是专家", AgentAssembler.resolveSysPrompt(def, List.of(), true));
    }

    @Test
    @DisplayName("系统提示词：库中提示词为空按空串处理")
    void shouldTreatNullPromptAsEmpty() {
        AiExpertAgent def = new AiExpertAgent();
        def.setSysPrompt(null);

        assertEquals("", AgentAssembler.resolveSysPrompt(def, List.of(), false));
        assertTrue(AgentAssembler.resolveSysPrompt(def, List.of("skill_a"), true).contains(DISCIPLINE_MARK));
    }

    @Test
    @DisplayName("只读工具池：仅取『只读且已注册成功』的业务工具")
    void shouldCollectOnlyRegisteredReadOnlyCodes() {
        List<AiTool> tools = List.of(
            tool("read_ok", AiTool.TYPE_READ_ONLY),
            tool("write_ok", "1"),
            tool("read_unregistered", AiTool.TYPE_READ_ONLY));

        List<String> pool = AgentAssembler.readOnlyRegisteredCodes(tools,
            List.of("read_ok", "write_ok"));

        assertEquals(List.of("read_ok"), pool);
    }

    @Test
    @DisplayName("工具白名单：业务工具 + 技能内置工具 + MCP 工具，并显式禁用联网检索")
    void shouldBuildToolsConfig() {
        ToolsConfig config = AgentAssembler.buildToolsConfig(List.of("t1"),
            List.of("skill_a"), new McpClientAssembly(List.of("srv"), List.of("mcp_a"),
                Map.of(), Map.of(), "sig"));

        assertEquals(List.of("t1", "load_skill_through_path", "read_skill", "mcp_a"), config.getAllow());
        assertEquals(List.of("web_search", "web_fetch"), config.getDeny());
    }

    @Test
    @DisplayName("工具白名单：无技能无 MCP 时仅业务工具")
    void shouldBuildMinimalToolsConfig() {
        ToolsConfig config = AgentAssembler.buildToolsConfig(List.of("t1"), List.of(),
            McpClientAssembly.empty());

        assertEquals(List.of("t1"), config.getAllow());
        assertFalse(config.getAllow().contains("read_skill"));
    }

    @Test
    @DisplayName("记忆抽取触发策略：never / throttled / 其余按 always")
    void shouldResolveFlushTrigger() {
        AiMemoryConfigBo never = new AiMemoryConfigBo();
        never.setFlushMode("never");
        assertEquals(MemoryConfig.FlushMode.NEVER, AgentAssembler.resolveFlushTrigger(never).mode());

        AiMemoryConfigBo throttled = new AiMemoryConfigBo();
        throttled.setFlushMode("throttled");
        throttled.setFlushThrottleMinutes(5);
        assertEquals(MemoryConfig.FlushMode.THROTTLED, AgentAssembler.resolveFlushTrigger(throttled).mode());
        assertEquals(Duration.ofMinutes(5), AgentAssembler.resolveFlushTrigger(throttled).minGap());

        AiMemoryConfigBo always = new AiMemoryConfigBo();
        always.setFlushMode("always");
        assertEquals(MemoryConfig.FlushMode.ALWAYS, AgentAssembler.resolveFlushTrigger(always).mode());
    }
}
