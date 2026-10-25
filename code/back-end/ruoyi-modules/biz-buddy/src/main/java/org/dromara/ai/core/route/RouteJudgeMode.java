package org.dromara.ai.core.route;

import org.dromara.common.core.utils.StringUtils;

/**
 * 结构化决策的执行模式（docs/21 §5.4）。
 *
 * <p>{@link #SHADOW} 为启用后的默认档：决策模型只留痕、不接管路由。
 * {@link #GATE} 已为<b>真实短路接管</b>——入口轮之前判定，高置信且选项合法即短路到专家直答。
 * {@link #PRESELECT} <b>尚未实现</b>，当前按影子语义执行，由调用方首用时打一条 WARN 说明，
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
     * 闸门：高置信且选项合法则短路工具决策（真实接管）
     */
    GATE("gate", "闸门"),

    /**
     * 候选预筛：候选过多时先缩到 top-k（尚未实现，当前按影子语义执行）
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