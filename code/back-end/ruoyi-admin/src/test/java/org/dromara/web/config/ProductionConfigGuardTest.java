package org.dromara.web.config;

import org.dromara.web.config.ProductionConfigGuard.SampleSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductionConfigGuard} 单元测试：默认（含非 {@code prod}）命中出厂示例值即拒绝启动；
 * 非 {@code prod} 可用 {@code warn} 开关显式降级；{@code prod} 下开关被忽略；替换后放行。
 */
@DisplayName("配置收口自检")
class ProductionConfigGuardTest {

    /**
     * 构造一个把全部出厂示例值原样填入的环境。
     *
     * @return 未收口环境
     */
    private static MockEnvironment sampleEnv() {
        MockEnvironment environment = new MockEnvironment();
        for (SampleSecret secret : ProductionConfigGuard.SAMPLE_SECRETS) {
            environment.setProperty(secret.key(), secret.sampleValue());
        }
        return environment;
    }

    /**
     * 构造一个把全部出厂示例值替换为自有值的环境。
     *
     * @param profile 激活的 profile
     * @return 已收口环境
     */
    private static MockEnvironment replacedEnv(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        for (SampleSecret secret : ProductionConfigGuard.SAMPLE_SECRETS) {
            environment.setProperty(secret.key(), "changed-" + secret.key());
        }
        return environment;
    }

    @Test
    @DisplayName("prod：仍为出厂示例值则拒绝启动并列出命中项")
    void shouldRejectSampleSecretsInProd() {
        MockEnvironment environment = sampleEnv();
        environment.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new ProductionConfigGuard(environment).afterPropertiesSet());

        assertTrue(ex.getMessage().contains("sa-token.jwt-secret-key"));
        assertTrue(ex.getMessage().contains("bizbuddy.ai.crypto-key"));
        assertTrue(ex.getMessage().contains("api-decrypt.privateKey"));
    }

    @Test
    @DisplayName("非 prod（dev / 无 profile）：默认同样拒绝启动——堵住「默认部署落在护栏盲区」")
    void shouldRejectOutsideProdByDefault() {
        MockEnvironment devEnv = sampleEnv();
        devEnv.setActiveProfiles("dev");
        IllegalStateException devEx = assertThrows(IllegalStateException.class,
            () -> new ProductionConfigGuard(devEnv).afterPropertiesSet());
        // 非 prod 的失败提示须给出逃生开关，否则使用者无从下手
        assertTrue(devEx.getMessage().contains(ProductionConfigGuard.MODE_KEY));

        MockEnvironment noProfileEnv = sampleEnv();
        assertThrows(IllegalStateException.class,
            () -> new ProductionConfigGuard(noProfileEnv).afterPropertiesSet());
    }

    @Test
    @DisplayName("非 prod：显式 warn 开关仅告警不阻断（本地开发逃生口）")
    void shouldOnlyWarnWhenExplicitlyDowngraded() {
        MockEnvironment environment = sampleEnv();
        environment.setActiveProfiles("dev");
        environment.setProperty(ProductionConfigGuard.MODE_KEY, "warn");

        new ProductionConfigGuard(environment).afterPropertiesSet();
    }

    @Test
    @DisplayName("prod：warn 开关被忽略，仍拒绝启动")
    void shouldIgnoreWarnSwitchInProd() {
        MockEnvironment environment = sampleEnv();
        environment.setActiveProfiles("prod");
        environment.setProperty(ProductionConfigGuard.MODE_KEY, "warn");

        assertThrows(IllegalStateException.class,
            () -> new ProductionConfigGuard(environment).afterPropertiesSet());
    }

    @Test
    @DisplayName("非 prod：开关取值无法识别时按 enforce 处理（fail-safe）")
    void shouldFallBackToEnforceOnUnknownMode() {
        MockEnvironment environment = sampleEnv();
        environment.setActiveProfiles("dev");
        environment.setProperty(ProductionConfigGuard.MODE_KEY, "whatever");

        assertThrows(IllegalStateException.class,
            () -> new ProductionConfigGuard(environment).afterPropertiesSet());
    }

    @Test
    @DisplayName("prod / dev：全部替换为自有值则放行（无需任何开关）")
    void shouldPassWhenAllSecretsReplaced() {
        new ProductionConfigGuard(replacedEnv("prod")).afterPropertiesSet();
        new ProductionConfigGuard(replacedEnv("dev")).afterPropertiesSet();

        assertTrue(ProductionConfigGuard.detect(replacedEnv("prod")::getProperty).isEmpty());
    }

    @Test
    @DisplayName("detect：逐项比对，仅列出仍是示例值的项")
    void shouldDetectOnlySampleValues() {
        MockEnvironment environment = sampleEnv();
        environment.setProperty("sa-token.jwt-secret-key", "my-own-secret");

        Set<String> hits = ProductionConfigGuard.detect(environment::getProperty)
            .stream()
            .collect(Collectors.toSet());

        assertEquals(ProductionConfigGuard.SAMPLE_SECRETS.size() - 1, hits.size());
        assertTrue(hits.stream().noneMatch(hit -> hit.contains("sa-token.jwt-secret-key")));
    }

    @Test
    @DisplayName("detect：配置缺失（null）不算命中")
    void shouldIgnoreMissingProperty() {
        MockEnvironment environment = new MockEnvironment();

        List<String> hits = ProductionConfigGuard.detect(environment::getProperty);

        assertTrue(hits.isEmpty());
    }
}
