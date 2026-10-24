package org.dromara.web.it;

import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.safety.AiSafetyGuard;
import org.dromara.ai.core.safety.IAiContentSafetyEngine;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：**整套 Spring 上下文能否装配起来**，以及 AI 配置读取（真 Redis）与明细统计（真库）是否联通。
 *
 * <p><b>为什么需要</b>：纯单测不起 Spring 容器，端到端测试只看接口行为——**「Bean 装配冲突 / 缺 Bean /
 * 配置解析错」这一类缺陷两边都抓不到**（典型如「同一个接口有多个实现时注入歧义」「Mapper 扫描不到」
 * 「运行期配置默认值装配不上」）。本测试用真实上下文 + 真实 PG / Redis（坐标取自 {@code application-dev.yml}
 * 的 localhost 配置）跑一遍。</p>
 *
 * <p><b>默认跳过</b>：需要 PG 栈（{@code 5432} / {@code 6379} / {@code 19530} 等）已启动。设环境变量
 * {@code BIZBUDDY_IT=true} 才执行。容器的 {@code sys_oss_config} 端点指向 {@code minio:9000}（容器内服务名），
 * 宿主机侧无法解析——但 OSS 客户端是**按需创建**的，装配期不使用，故不影响本测试。</p>
 *
 * <p><b>用随机端口的 Web 上下文</b>：本应用**不能在非 Web 上下文启动**——{@code ruoyi-common-doc} 的
 * {@code SpringDocConfig} 依赖 Web 自动配置提供的 {@code ServerProperties}，{@code webEnvironment = NONE}
 * 会让装配直接失败（实测）。随机端口既避开与正在运行的容器争抢 {@code 8080}，又能覆盖 Web 层装配。</p>
 *
 * <p><b>关掉调度客户端</b>（{@code snail-job.enabled=false}）：其端口取自 {@code 2${server.port}}，
 * 随机端口下会退化成无效端口；关掉后不注册客户端，也不影响本测试关注点（定时任务链路由端到端测试覆盖）。</p>
 *
 * @author BizBuddy
 */
@EnabledIfEnvironmentVariable(named = "BIZBUDDY_IT", matches = "(?i)true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // 本测试刻意以 dev + 出厂配置装配真 Redis / 真库，故把配置收口自检显式降级为「仅告警」
    properties = {
        "snail-job.enabled=false",
        "bizbuddy.security.guard.mode=warn"
    })
@DisplayName("集成测试：上下文装配 + AI 配置（真 Redis）与明细统计（真库）")
class AiContextIntegrationTest {

    /**
     * 审计明细表数量（清理范围 6 表；统计应逐表给出）
     */
    private static final int AUDIT_TABLE_COUNT = 6;

    @Autowired
    private AiSafetyGuard safetyGuard;

    @Autowired
    private AiRuntimeConfigStore runtimeConfigStore;

    @Autowired
    private IAiAuditRetentionService auditRetentionService;

    @Autowired
    private List<IAiContentSafetyEngine> safetyEngines;

    @Test
    @DisplayName("上下文装配成功，审核引擎两实现与运行期配置中心齐备")
    void shouldWireAiBeans() {
        assertNotNull(safetyGuard);
        assertNotNull(runtimeConfigStore);
        assertNotNull(auditRetentionService);

        Set<String> engineNames = safetyEngines.stream()
            .map(IAiContentSafetyEngine::engineName)
            .collect(Collectors.toSet());
        assertTrue(engineNames.containsAll(Set.of("local", "external")),
            "审核引擎应同时接入 local 与 external（多实现注入）：实际 " + engineNames);

        assertTrue(Set.of("local", "external").contains(safetyGuard.engineName()),
            "留痕引擎名应落在已接入范围内：实际 " + safetyGuard.engineName());
    }

    @Test
    @DisplayName("运行期配置走真 Redis（覆盖优先），超期统计走真库（Mapper 装配可用）")
    void shouldRoundTripRetentionThroughRealRedisAndDb() {
        AiAuditRetentionVo original = auditRetentionService.getRetention();
        assertNotNull(original, "读取保留策略失败：运行期配置未装配");
        int originalDays = original.getRetentionDays();
        try {
            AiAuditRetentionBo override = new AiAuditRetentionBo();
            override.setRetentionDays(123);
            auditRetentionService.saveRetention(override);

            AiAuditRetentionVo overridden = auditRetentionService.getRetention();
            assertEquals(123, overridden.getRetentionDays(), "Redis 覆盖值未生效（覆盖优先）");
            assertNotNull(overridden.getCutoff(), "保留天数 > 0 时应给出清理截止时间");
            assertEquals(AUDIT_TABLE_COUNT, overridden.getPending().size(),
                "超期统计应逐表覆盖 6 张审计明细表（真库查询结果）：实际 " + overridden.getPending().keySet());
        } finally {
            AiAuditRetentionBo restore = new AiAuditRetentionBo();
            restore.setRetentionDays(originalDays);
            auditRetentionService.saveRetention(restore);
        }
    }

}
