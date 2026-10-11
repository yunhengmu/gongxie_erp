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
 * {@link ProductionConfigGuard} 单元测试：prod 下拒绝出厂示例值，非 prod 不干预，替换后放行。
 */
@DisplayName("生产配置收口自检")
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
        assertTrue(ex.getMessage().contains("snail-job.token"));
    }

    @Test
    @DisplayName("prod：全部替换为自有值则放行")
    void shouldPassWhenAllSecretsReplaced() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        for (SampleSecret secret : ProductionConfigGuard.SAMPLE_SECRETS) {
            environment.setProperty(secret.key(), "changed-" + secret.key());
        }

        new ProductionConfigGuard(environment).afterPropertiesSet();

        assertTrue(ProductionConfigGuard.detect(environment::getProperty).isEmpty());
    }

    @Test
    @DisplayName("非 prod（dev / 无 profile）：即便是示例值也不干预")
    void shouldSkipOutsideProd() {
        MockEnvironment devEnv = sampleEnv();
        devEnv.setActiveProfiles("dev");
        new ProductionConfigGuard(devEnv).afterPropertiesSet();

        MockEnvironment noProfileEnv = sampleEnv();
        new ProductionConfigGuard(noProfileEnv).afterPropertiesSet();
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
