package org.dromara.ai.core.safety;

import org.dromara.common.core.utils.StringUtils;

/**
 * 安全合规命中后的处置动作。
 *
 * @author BizBuddy
 */
public enum SafetyAction {

    /**
     * 只留痕（原文放行）——灰度默认
     */
    LOG("log", "只记录"),

    /**
     * 命中内容替换为掩码后放行
     */
    MASK("mask", "脱敏放行"),

    /**
     * 阻断（输入回拒答、输出以拒答替代）
     */
    BLOCK("block", "阻断");

    private final String code;

    private final String label;

    SafetyAction(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * @return 落库用的稳定编码
     */
    public String code() {
        return code;
    }

    /**
     * @return 中文名（留痕展示用）
     */
    public String label() {
        return label;
    }

    /**
     * 解析动作编码（非法值回落 {@link #LOG}，避免配置写错即阻断）。
     *
     * @param code 动作编码
     * @return 动作
     */
    public static SafetyAction parse(String code) {
        if (StringUtils.isNotBlank(code)) {
            for (SafetyAction action : values()) {
                if (action.code.equalsIgnoreCase(code.trim())) {
                    return action;
                }
            }
        }
        return LOG;
    }

}