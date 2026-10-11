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
 * 生产配置收口：{@code prod} profile 启动时校验「出厂示例密钥 / 口令」是否仍未替换，命中即**拒绝启动**。
 *
 * <p>背景：框架与平台在配置里自带一批可直接使用的示例密钥（Sa-Token JWT 秘钥、接口加解密 RSA 密钥对、
 * 模型密钥加密根、数据库 / Redis / Milvus 口令、SnailJob 接入令牌），默认值随源码公开，若原样上生产
 * 等同于把认证根交出去。本类把这些「示例值」集中登记，在 {@code prod} 下逐项比对，仍是示例值就 fail-fast，
 * 而不是等出了问题再排查。</p>
 *
 * <p>仅校验**配置文件**里的示例值；非 {@code prod}（本地 / 容器默认 {@code dev}）不受影响。MCP 出口口令
 * 不在此列——它是运行期配置（Redis，见 {@code AiMcpAuthFilter}），且未配置时校验收口会拒绝所有请求
 * （fail-closed），本身即安全。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionConfigGuard implements InitializingBean {

    /**
     * 生产 profile 名。
     */
    private static final String PROD_PROFILE = "prod";

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
     * 出厂示例值清单：{@code prod} 下沿用任一项即视为未收口。
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
     * 上下文就绪即校验（仅 {@code prod}）：命中出厂示例值则抛异常阻断启动。
     */
    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            return;
        }
        List<String> violations = detect(environment::getProperty);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(buildMessage(violations));
        }
        log.info("生产配置自检通过：未发现出厂示例密钥 / 口令");
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
     * 构造明确的失败提示，列出所有未替换项与对应环境变量。
     *
     * @param violations 命中项描述列表
     * @return 异常消息
     */
    private static String buildMessage(List<String> violations) {
        return "生产配置自检未通过：以下配置仍为出厂示例值，请通过环境变量替换后再启动\n  - "
            + String.join("\n  - ", violations)
            + "\n环境变量与说明见 docs/configuration.md「生产环境必改清单」。";
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
