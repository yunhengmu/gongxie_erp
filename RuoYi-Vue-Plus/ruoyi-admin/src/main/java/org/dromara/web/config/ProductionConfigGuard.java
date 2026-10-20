package org.dromara.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 配置收口自检：校验「出厂示例密钥 / 口令」是否仍未替换，命中即按收口模式处置。
 *
 * <p>背景：框架与平台在配置里自带一批可直接使用的示例密钥（Sa-Token JWT 秘钥、接口加解密 RSA 密钥对、
 * 模型密钥加密根、数据库 / Redis / Milvus 口令、SnailJob 接入令牌），默认值随源码公开，若原样投入使用
 * 等同于把认证根交出去。本类把这些「示例值」集中登记，启动时逐项比对，而不是等出了问题再排查。</p>
 *
 * <p><b>为何对全部 profile 生效</b>：护栏原先只认 {@code prod}，而两套容器编排的默认 profile 是 {@code dev}
 * （挂载的配置文件必须名为 {@code application-dev.yml}，直接改默认 profile 会让配置解析失效），
 * 于是「按默认方式部署」恰好落在护栏盲区——出厂口令能原样跑起来。现改为**默认对所有 profile 检查**，
 * 并按 {@link #MODE_KEY} 分级处置：{@code enforce}（默认）命中即拒绝启动，且 {@code prod} 下恒为该档、
 * 开关不可覆盖；{@code warn} 仅告警不阻断，只允许非 {@code prod} 显式启用，作为本地开发沿用出厂值的逃生口。</p>
 *
 * <p>仅校验**配置文件**里的示例值。MCP 出口口令不在此列——它是运行期配置（Redis，见 {@code AiMcpAuthFilter}），
 * 且未配置时校验收口会拒绝所有请求（fail-closed），本身即安全。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionConfigGuard implements InitializingBean {

    /**
     * 生产 profile 名：该 profile 下护栏恒为 {@link #MODE_ENFORCE}，开关不可覆盖。
     */
    private static final String PROD_PROFILE = "prod";

    /**
     * 收口模式配置键：{@code enforce}（默认，命中即拒绝启动）/ {@code warn}（仅告警不阻断，只允许非 prod 显式启用）。
     *
     * <p>键名须与部署侧的环境变量严格对应：{@code BIZBUDDY_SECURITY_GUARD_MODE} 按宽松绑定映射到的正是
     * {@code bizbuddy.security.guard.mode}，故此处不可另起名字（曾用 {@code production-guard} 导致取不到值）。</p>
     */
    static final String MODE_KEY = "bizbuddy.security.guard.mode";

    /**
     * 拒绝启动档：命中出厂示例值即中断启动。
     */
    private static final String MODE_ENFORCE = "enforce";

    /**
     * 仅告警档：命中只记 WARN、不阻断启动（本地开发逃生口）。
     */
    private static final String MODE_WARN = "warn";

    /**
     * 接口加密：出厂自带的响应公钥（与前端解密私钥成对，随源码公开）。
     */
    static final String SAMPLE_API_DECRYPT_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDm6u8q8X78onmbU4wGMq3b4ufbWE18YzuWo5jwkUlwWPTPcENbZYtveyTepp2Od1CTDcjhTUmVYvFkhaCF46UfOxZrwoSZc3jf3WXXd0hLOPBHuulynknj2KsWvKDuRig7J2o4KbDhyl0nnlUiMrIiD0tv1rBKpNCJ/T+MN0bERQIDAQAB";

    /**
     * 接口加密：出厂自带的请求私钥（与前端加密公钥成对，随源码公开）。
     */
    static final String SAMPLE_API_DECRYPT_PRIVATE_KEY =
        "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAO8QO5Eg4zehk9aP1SShzmlCSVHg8Ufr9yWeN4WqMMsiAPJC+PGGCoBlAD4T14Pqq7oWxc+Yrx2Nwv6eHdwUfPilfjveMO87dK977zIvdVFDSfalGBDZrTUwmzL5bBNkIFhZ/RWctEi8A1ShZCDL2/P3irtVrjh2DsDX/cgJ/7EDAgMBAAECgYEAhNZAQyRDHWZq/45soS5Hw7VRiG21pIE5k22W7G7lLfp3DCaqrYoNy8pTmCruVh7PzVdaE0CEDaf38gNqFCBOT8iTFQiYV3am4W3hsEQM5wmVBeTvCM5P2jsaaBQbqmneRjiZVbs6ha205JSho1Oc85NbaZa8gFVjwZgZWJrbzgECQQD/iZWhkRPtbdeai/Xk7D/eIXKh1Gxid0rWKQq8ikxbaiergn47XzNKrpROVyka3Gn85o7jJphgxp99R3r8sH71AkEA738Dn7xs+I4Y+MLa2EcT78JG3f/VhlWS/ks3qGJ2dfqwS7ntnmf5Q+2Xw+9UcuiK/TxD8K/0inSCkIMeWBOFFwJBAIoTebq3faEJfTqQ7ekojsokIKC4+2epNdLKknaV8/RhQ9Y0yKikJD7yXkiGaDuPZeW1Xvf2XtfL+1niSd5IMBECQDCOOMbe5dzyuj9dCg+FQZZ/dey2XK0Slm22BD/ATrIWtD12IaXXAKNz/Sv9TsrJOLykxkV69wJHIt13p+RFeNsCQGn5XGRn4ZCRVCesJYXyx29MTqkl8sD/gzYcURTZYjHqX2EvtvAyC6gBm9H0EbxmHIi4Oq0tITzklCXj5SpvBEw=";

    /**
     * 出厂示例值清单：任何 profile 下沿用任一项即视为未收口（{@code prod} 直接拒绝启动）。
     */
    static final List<SampleSecret> SAMPLE_SECRETS = List.of(
        new SampleSecret("sa-token.jwt-secret-key", "abcdefghijklmnopqrstuvwxyz", "Sa-Token JWT 秘钥"),
        new SampleSecret("bizbuddy.ai.crypto-key", "bizbuddy-ai-2026", "模型密钥加密根"),
        new SampleSecret("api-decrypt.publicKey", SAMPLE_API_DECRYPT_PUBLIC_KEY, "接口加密响应公钥"),
        new SampleSecret("api-decrypt.privateKey", SAMPLE_API_DECRYPT_PRIVATE_KEY, "接口加密请求私钥"),
        new SampleSecret("spring.datasource.dynamic.datasource.master.password", "Zl@2026", "数据库口令"),
        new SampleSecret("spring.data.redis.password", "Zl@2026", "Redis 口令"),
        new SampleSecret("bizbuddy.ai.milvus.password", "Milvus", "Milvus 口令"),
        new SampleSecret("snail-job.token", "SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT", "SnailJob 接入令牌")
    );

    private final Environment environment;

    /**
     * 上下文就绪即校验：命中出厂示例值则按收口模式处置（默认拒绝启动）。
     */
    @Override
    public void afterPropertiesSet() {
        boolean prod = environment.acceptsProfiles(Profiles.of(PROD_PROFILE));
        String configured = configuredMode();
        if (prod && MODE_WARN.equals(configured)) {
            // 生产不允许用开关绕过「命中出厂值即拒绝启动」的硬约束，此处仅提示该开关被忽略
            log.warn("[配置收口] {}={} 在 {} 下被忽略：生产环境恒为 {}", MODE_KEY, MODE_WARN, PROD_PROFILE, MODE_ENFORCE);
        }
        String mode = prod ? MODE_ENFORCE : configured;

        List<String> violations = detect(environment::getProperty);
        if (violations.isEmpty()) {
            log.info("[配置收口] 自检通过：未发现出厂示例密钥 / 口令（profile={}，mode={}）", activeProfiles(), mode);
            return;
        }
        if (MODE_WARN.equals(mode)) {
            log.warn("[配置收口] 命中 {} 项出厂示例值，{} 模式仅告警不阻断——切勿用于生产环境：\n  - {}",
                violations.size(), MODE_WARN, String.join("\n  - ", violations));
            return;
        }
        throw new IllegalStateException(buildMessage(violations, prod));
    }

    /**
     * 读取收口模式配置；缺省或取值无法识别时按 {@link #MODE_ENFORCE} 处理（fail-safe）。
     *
     * @return {@link #MODE_ENFORCE} 或 {@link #MODE_WARN}
     */
    private String configuredMode() {
        String configured = environment.getProperty(MODE_KEY);
        if (configured == null || configured.isBlank()) {
            return MODE_ENFORCE;
        }
        String trimmed = configured.trim();
        if (MODE_ENFORCE.equalsIgnoreCase(trimmed) || MODE_WARN.equalsIgnoreCase(trimmed)) {
            return trimmed.toLowerCase();
        }
        log.warn("[配置收口] 无法识别的 {} 取值「{}」，按 {} 处理（可选值：{} / {}）",
            MODE_KEY, configured, MODE_ENFORCE, MODE_ENFORCE, MODE_WARN);
        return MODE_ENFORCE;
    }

    /**
     * 当前激活的 profile（仅为日志可读性）。
     *
     * @return 逗号分隔的 profile；未激活任何 profile 时返回 {@code default}
     */
    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }

    /**
     * 比对出厂示例值，返回仍在使用默认值的配置项描述。
     *
     * @param getProperty 按属性键读取配置值（允许返回 null）
     * @return 命中项描述列表；全部已替换时为空
     */
    static List<String> detect(UnaryOperator<String> getProperty) {
        List<String> violations = new ArrayList<>();
        for (SampleSecret secret : SAMPLE_SECRETS) {
            if (secret.sampleValue().equals(getProperty.apply(secret.key()))) {
                violations.add(secret.key() + "（" + secret.description() + "）");
            }
        }
        return violations;
    }

    /**
     * 构造明确的失败提示：列出所有未替换项，并在非 {@code prod} 下给出本地开发的逃生开关。
     *
     * @param violations 命中项描述列表
     * @param prod       是否 {@code prod} profile（决定是否提示逃生开关）
     * @return 异常消息
     */
    private static String buildMessage(List<String> violations, boolean prod) {
        String message = "配置收口自检未通过：以下配置仍为出厂示例值，请先替换再启动\n  - "
            + String.join("\n  - ", violations);
        if (!prod) {
            message += "\n（本地开发确需沿用出厂值：在 .env 设 " + MODE_KEY + "=" + MODE_WARN
                + "；生产环境该开关无效）";
        }
        return message + "\n环境变量与说明见 docs/configuration.md「生产环境必改清单」。";
    }

    /**
     * 出厂示例密钥 / 口令。
     *
     * @param key         配置属性键
     * @param sampleValue 出厂示例值
     * @param description 中文说明
     */
    record SampleSecret(String key, String sampleValue, String description) {
    }
}
