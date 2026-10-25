package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对话偏好视图（时区 / 语言，REQ-AI-31）。
 *
 * <p>三段值：{@code platformXxx} 为平台默认（只读），{@code timezone} / {@code locale} 为人级已设置值
 * （未设置时为 {@code null}），{@code effectiveXxx} 为「人级 → 平台默认」的实际生效值。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiChatPrefVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 平台默认时区（只读）
     */
    private String platformTimezone;

    /**
     * 平台默认语言（只读）
     */
    private String platformLocale;

    /**
     * 人级已设置时区（未设置为 null）
     */
    private String timezone;

    /**
     * 人级已设置语言（未设置为 null）
     */
    private String locale;

    /**
     * 实际生效时区（人级 → 平台默认）
     */
    private String effectiveTimezone;

    /**
     * 实际生效语言（人级 → 平台默认）
     */
    private String effectiveLocale;

}
