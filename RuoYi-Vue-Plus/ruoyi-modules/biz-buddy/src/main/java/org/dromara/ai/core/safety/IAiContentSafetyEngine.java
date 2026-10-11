package org.dromara.ai.core.safety;

/**
 * 内容安全审核引擎（可插拔）。
 *
 * <p>本地词库为默认实现（{@link LocalKeywordSafetyEngine}）；后续接入外部内容安全 API 时
 * 新增实现并调整 {@code bizbuddy.ai.security.engine} 即可，调用方无感。</p>
 *
 * @author BizBuddy
 */
public interface IAiContentSafetyEngine {

    /**
     * 校验一段文本。
     *
     * @param text  待校验文本
     * @param scene 场景
     * @return 校验结果（未命中返回 {@link ContentSafetyResult#miss()}）
     */
    ContentSafetyResult check(String text, SafetyScene scene);

    /**
     * @return 引擎名（落库留痕用，如 {@code local} / {@code external}）
     */
    String engineName();

}