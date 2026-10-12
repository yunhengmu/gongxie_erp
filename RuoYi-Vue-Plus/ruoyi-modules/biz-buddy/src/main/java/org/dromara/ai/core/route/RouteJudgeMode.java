package org.dromara.ai.core.route;

import org.dromara.common.core.utils.StringUtils;

/**
 * 结构化决策的执行模式（docs/21 §5.4）。
 *
 * <p>本批次<b>只实现「影子」的真实语义</b>：{@link #GATE} / {@link #PRESELECT} 能解析，
 * 但按影子行为执行（只留痕不接管），并由调用方首用时打一条 WARN 说明「短路接管待 T9」，
 * 不静默降级。</p>
 *
 * @author BizBuddy
 */
public enum RouteJudgeMode {

    /**
     * 影子：现有工具决策照旧，决策模型只旁路留痕
     */
    SHADOW("shadow", "影子"),

    /**
     * 闸门：高置信且选项合法则短路工具决策（本批次按影子执行）
     */
    GATE("gate", "闸门"),

    /**
     * 候选预筛：候选过多时先缩到 top-k（可选，本批次按影子执行）
     */
    PRESELECT("preselect", "候选预筛");

    private final String wire;

    private final String label;

    RouteJudgeMode(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /**
     * @return 配置取值（shadow / gate / preselect）
     */
    public String wire() {
        return wire;
    }

    /**
     * @return 中文名（日志用）
     */
    public String label() {
        return label;
    }

    /**
     * 解析配置取值：空 / 未知一律回退 {@link #SHADOW}（不报错，与「关闭态不校验」同源）。
     *
     * @param value 配置取值（可空）
     * @return 执行模式
     */
    public static RouteJudgeMode parse(String value) {
        if (StringUtils.isBlank(value)) {
            return SHADOW;
        }
        for (RouteJudgeMode mode : values()) {
            if (mode.wire.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return SHADOW;
    }

}