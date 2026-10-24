package org.dromara.ai.core.agent;

import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiCompactionConfigBo;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiPlanConfigBo;
import org.dromara.ai.domain.bo.AiRouteBudgetConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.domain.bo.AiSecurityConfigPatchBo;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link AiRuntimeConfigStore} 单元测试：Redis 覆盖优先与配置默认值回落。
 */
@DisplayName("运行期策略配置存储")
class AiRuntimeConfigStoreTest {

    private static final String COMPACTION_KEY = "bizbuddy:ai:runtime:compaction";
    private static final String PLAN_KEY = "bizbuddy:ai:runtime:plan";
    private static final String MEMORY_KEY = "bizbuddy:ai:runtime:memory";
    private static final String WORKSPACE_KEY = "bizbuddy:ai:runtime:workspace";
    private static final String MCP_SERVER_KEY = "bizbuddy:ai:runtime:mcp-server";
    private static final String ROUTE_BUDGET_KEY = "bizbuddy:ai:runtime:route-budget";
    private static final String SECURITY_KEY = "bizbuddy:ai:runtime:security";

    private final AiProperties aiProperties = new AiProperties();
    private final AiRuntimeConfigStore store = new AiRuntimeConfigStore(aiProperties);

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("无覆盖时压缩策略回落默认配置")
    void shouldFallbackCompactionToDefaults() {
        aiProperties.getChat().setCompactionTriggerMessages(11);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            AiCompactionConfigBo policy = store.compaction();
            assertEquals(11, policy.getTriggerMessages());
            assertEquals(aiProperties.getChat().isCompactionEnabled(), policy.isEnabled());
        }
    }

    @Test
    @DisplayName("有覆盖时压缩策略取缓存值")
    void shouldReadCompactionFromCache() {
        AiCompactionConfigBo cached = new AiCompactionConfigBo();
        cached.setTriggerMessages(99);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(COMPACTION_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            assertEquals(99, store.compaction().getTriggerMessages());
        }
    }

    @Test
    @DisplayName("保存压缩策略写入 Redis")
    void shouldSaveCompaction() {
        AiCompactionConfigBo bo = new AiCompactionConfigBo();
        bo.setEnabled(true);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updateCompaction(bo);
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("计划模式配置回落并补全计划目录")
    void shouldResolvePlan() {
        aiProperties.getChat().setPlanFileDirectory("plans-root");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            AiPlanConfigBo policy = store.plan();
            assertEquals("plans-root", policy.getPlanFileDirectory());
        }

        AiPlanConfigBo cached = new AiPlanConfigBo();
        cached.setEnabled(true);
        cached.setPlanFileDirectory(" ");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(PLAN_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            AiPlanConfigBo policy = store.plan();
            assertTrue(policy.isEnabled());
            assertEquals("plans-root", policy.getPlanFileDirectory());
        }
    }

    @Test
    @DisplayName("保存计划模式配置写入 Redis")
    void shouldSavePlan() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updatePlan(new AiPlanConfigBo());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("记忆配置回落并补全抽取策略")
    void shouldResolveMemory() {
        aiProperties.getChat().setMemoryFlushMode("throttled");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertEquals("throttled", store.memory().getFlushMode());
        }

        AiMemoryConfigBo cached = new AiMemoryConfigBo();
        cached.setMaxContextTokens(1234);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(MEMORY_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            AiMemoryConfigBo policy = store.memory();
            assertEquals(1234, policy.getMaxContextTokens());
            assertEquals("throttled", policy.getFlushMode());
        }
    }

    @Test
    @DisplayName("保存记忆配置写入 Redis")
    void shouldSaveMemory() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updateMemory(new AiMemoryConfigBo());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("工作区配置回落与缓存补全空清单")
    void shouldResolveWorkspace() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertNotNull(store.workspace().getAdditionalContextFiles());
        }

        AiWorkspaceConfigBo cached = new AiWorkspaceConfigBo();
        cached.setAdditionalContextFiles(null);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(WORKSPACE_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            assertTrue(store.workspace().getAdditionalContextFiles().isEmpty());
        }
    }

    @Test
    @DisplayName("保存工作区配置写入 Redis")
    void shouldSaveWorkspace() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updateWorkspace(new AiWorkspaceConfigBo());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("MCP 出口配置回落默认与缓存读取")
    void shouldResolveMcpServer() {
        aiProperties.getChat().setMcpServerAuthEnabled(false);
        aiProperties.getChat().setMcpServerToken("token-default");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            AiMcpServerConfigBo policy = store.mcpServer();
            assertFalse(policy.isAuthEnabled());
            assertEquals("token-default", policy.getToken());
        }

        AiMcpServerConfigBo cached = new AiMcpServerConfigBo();
        cached.setToken("cached-token");
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(MCP_SERVER_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            assertEquals("cached-token", store.mcpServer().getToken());
        }
    }

    @Test
    @DisplayName("保存 MCP 出口配置写入 Redis")
    void shouldSaveMcpServer() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updateMcpServer(new AiMcpServerConfigBo());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("工作区默认附加文件清单来自配置")
    void shouldUseConfiguredWorkspaceFiles() {
        aiProperties.getChat().setWorkspaceAdditionalContextFiles(List.of("a.md", "b.md"));
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            assertEquals(List.of("a.md", "b.md"), store.workspace().getAdditionalContextFiles());
        }
    }

    @Test
    @DisplayName("编排预算回落默认与缓存读取")
    void shouldResolveRouteBudget() {
        aiProperties.getChat().setRouteBudgetExpertTokens(12345L);
        aiProperties.getChat().setRouteBudgetSeconds(90);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(anyString())).thenReturn(null);
            AiRouteBudgetConfigBo policy = store.routeBudget();
            assertEquals(12345L, policy.getExpertTokens());
            assertEquals(90, policy.getWallSeconds());
        }

        AiRouteBudgetConfigBo cached = new AiRouteBudgetConfigBo();
        cached.setExpertTokens(777L);
        cached.setWallSeconds(30);
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(ROUTE_BUDGET_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            AiRouteBudgetConfigBo policy = store.routeBudget();
            assertEquals(777L, policy.getExpertTokens());
            assertEquals(30, policy.getWallSeconds());
        }
    }

    @Test
    @DisplayName("保存编排预算配置写入 Redis")
    void shouldSaveRouteBudget() {
        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            store.updateRouteBudget(new AiRouteBudgetConfigBo());
            redis.verify(() -> RedisUtils.setCacheObject(anyString(), anyString()));
        }
    }

    @Test
    @DisplayName("安全合规部分更新只改本页签提交的字段")
    void shouldMergeSecurityPatch() {
        AiSecurityConfigBo cached = new AiSecurityConfigBo();
        cached.setEnabled(true);
        cached.setEngine("external");
        cached.setAction("mask");
        cached.setExcerptChars(300);
        cached.setProviderEndpoint("https://example.com/moderate");
        cached.setProviderApiKey("secret-key");

        AiSecurityConfigPatchBo patch = new AiSecurityConfigPatchBo();
        patch.setMaskPii(false);

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(SECURITY_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            store.updateSecurity(patch);
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            redis.verify(() -> RedisUtils.setCacheObject(eq(SECURITY_KEY), json.capture()));
            AiSecurityConfigBo saved = JsonUtils.parseObject(json.getValue(), AiSecurityConfigBo.class);
            assertFalse(saved.isMaskPii(), "PII 开关应被本次提交覆盖");
            assertTrue(saved.isEnabled(), "内容审核开关未提交，应沿用已存值");
            assertEquals("external", saved.getEngine());
            assertEquals("mask", saved.getAction());
            assertEquals(300, saved.getExcerptChars());
            assertEquals("https://example.com/moderate", saved.getProviderEndpoint());
            assertEquals("secret-key", saved.getProviderApiKey());
        }
    }

    @Test
    @DisplayName("安全合规部分更新不改未提交字段且密钥留空沿用已存值")
    void shouldKeepUnsubmittedSecurityFields() {
        AiSecurityConfigBo cached = new AiSecurityConfigBo();
        cached.setMaskPii(false);
        cached.setProviderApiKey("secret-key");

        AiSecurityConfigPatchBo patch = new AiSecurityConfigPatchBo();
        patch.setExcerptChars(400);
        patch.setProviderApiKey("");

        try (MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            redis.when(() -> RedisUtils.getCacheObject(SECURITY_KEY)).thenReturn(JsonUtils.toJsonString(cached));
            store.updateSecurity(patch);
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            redis.verify(() -> RedisUtils.setCacheObject(eq(SECURITY_KEY), json.capture()));
            AiSecurityConfigBo saved = JsonUtils.parseObject(json.getValue(), AiSecurityConfigBo.class);
            assertFalse(saved.isMaskPii(), "未提交的 PII 开关应沿用已存值");
            assertEquals("secret-key", saved.getProviderApiKey(), "密钥留空应沿用已存值");
            assertEquals(400, saved.getExcerptChars(), "本次提交的字段应生效");
        }
    }
}