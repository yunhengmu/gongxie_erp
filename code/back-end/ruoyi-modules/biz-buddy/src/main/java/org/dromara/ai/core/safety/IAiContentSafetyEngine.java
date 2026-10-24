package org.dromara.ai.core.safety;

import org.dromara.ai.domain.bo.AiSecurityConfigBo;

/**
 * 内容安全审核引擎（可插拔）。
 *
 * <p>本地词库为默认实现（{@link LocalKeywordSafetyEngine}），外部内容安全 API 为
 * {@link ExternalApiSafetyEngine}；由 {@link AiSafetyGuard} 按运行期配置的
 * {@code bizbuddy.ai.security.engine} 选择，调用方无感。</p>
 *
 * @author BizBuddy
 */
public interface IAiContentSafetyEngine {

    /**
     * 校验一段文本。
     *
     * @param text   待校验文本
     * @param scene  场景
     * @param config 当前生效的安全合规配置：外部引擎据此取端点 / 密钥（{@link AiSafetyGuard} 已带
     *               短 TTL 缓存，传入可避免逐片再读一次运行期配置）；本地引擎忽略之
     * @return 校验结果（未命中返回 {@link ContentSafetyResult#miss()}，任何异常均降级为未命中）
     */
    ContentSafetyResult check(String text, SafetyScene scene, AiSecurityConfigBo config);

    /**
     * 该引擎在当前配置下是否可用；不可用时由 {@link AiSafetyGuard} 降级为本地词库并告警。
     *
     * @param config 当前生效的安全合规配置
     * @return 是否可用（本地词库恒为可用）
     */
    default boolean available(AiSecurityConfigBo config) {
        return true;
    }

    /**
     * @return 引擎名（落库留痕用，如 {@code local} / {@code external}）
     */
    String engineName();

}