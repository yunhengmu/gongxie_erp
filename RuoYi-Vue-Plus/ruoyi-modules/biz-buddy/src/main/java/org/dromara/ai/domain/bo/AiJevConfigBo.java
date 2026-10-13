package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结构化决策（专家路由判断）配置业务对象。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.jev.*}，页面保存后写入 Redis 覆盖默认值
 * （键 {@code bizbuddy:ai:runtime:jev}），下一次编排即按新模式 / 阈值生效。</p>
 *
 * <p><b>决策模型的连接信息不在此配置内</b>：服务地址 / 模型标识 / 密钥统一由「模型配置」
 * （{@code ai_model_config}，类型 {@code decision}）管理，本对象只引用其 {@link #modelConfigId}。
 * 服务端在保存时强校验：{@code enabled=true} 时该模型必须<b>存在、启用、为 decision 类型且已配置密钥</b>
 * （见 {@code AiRuntimeController#updateJev}）；任一不满足即报错，避免「打开了开关但没有可用决策模型」
 * 的半残状态（口径见 docs/21 §1.3）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiJevConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总开关：关闭＝零外呼、路由完全由小Z 负责（默认）
     */
    private boolean enabled;

    /**
     * 所选决策模型（「模型配置」主键）；{@code enabled=true} 时必填（由服务端校验，不用分组注解，
     * 以免影响 {@code enabled=false} 时的保存）
     */
    private Long modelConfigId;

    /**
     * 判定超时（秒；与「模型配置」的通用超时无关——判定在路由关键路径上，需要更短的超时）
     */
    @Min(value = 1, message = "判定超时至少 1 秒")
    @Max(value = 120, message = "判定超时过大")
    private int timeoutSeconds = 1;

    /**
     * 执行模式：shadow 影子（只留痕不接管）/ gate 闸门（高置信接管）/ preselect 候选预筛（可选）
     */
    @Pattern(regexp = "^(shadow|gate|preselect)$", message = "执行模式只能是 shadow / gate / preselect")
    private String mode = "shadow";

    /**
     * 闸门模式的**基础**置信度阈值（0~1）：**候选数 ≤5** 时使用（docs/22 §3.1 A）。
     *
     * <p>闭集打分器的概率在 N 个选项上归一化，候选越多单选置信度天然越低，一把尺子必然失真；
     * 故按候选数分档——6~10 用 {@link #confidenceThresholdMedium}、&gt;10 用
     * {@link #confidenceThresholdLarge}。判定顺序固定为「分桶基础阈值 → margin 二次过滤」。</p>
     */
    @DecimalMin(value = "0.0", message = "置信度阈值不能小于 0")
    @DecimalMax(value = "1.0", message = "置信度阈值不能大于 1")
    private double confidenceThreshold = 0.75;

    /**
     * 闸门模式的基础置信度阈值：候选数 6~10 档（0~1）
     */
    @DecimalMin(value = "0.0", message = "置信度阈值不能小于 0")
    @DecimalMax(value = "1.0", message = "置信度阈值不能大于 1")
    private double confidenceThresholdMedium = 0.65;

    /**
     * 闸门模式的基础置信度阈值：候选数 &gt;10 档（0~1）
     */
    @DecimalMin(value = "0.0", message = "置信度阈值不能小于 0")
    @DecimalMax(value = "1.0", message = "置信度阈值不能大于 1")
    private double confidenceThresholdLarge = 0.55;

    /**
     * margin 二次过滤比值 r：基础阈值达标后，还要求 {@code top1 / top2 ≥ r}（docs/22 §3.1 A）。
     *
     * <p>裸 confidence 分不清「0.76/0.15」与「0.76/0.70」——后者是「勉强第一」型误接管，也是
     * 降级（时延黑洞）的主要来源；margin 的价值主要在压掉这一类。取 1.0 等价不启用（top1/top2 恒 ≥1）。</p>
     */
    @DecimalMin(value = "1.0", message = "margin 比值不能小于 1")
    @DecimalMax(value = "20.0", message = "margin 比值过大")
    private double marginRatio = 2.0;

    /**
     * 决策不可用时是否回落工具决策
     */
    private boolean failOpen = true;

    /**
     * 外呼 {@code state} 的字符上限（前置保护）
     */
    @Min(value = 100, message = "state 上限过小")
    @Max(value = 65536, message = "state 上限过大")
    private int stateMaxChars = 4000;

    /**
     * 是否落库概率分布
     */
    private boolean persistProbabilities = true;

    /**
     * 是否启用熔断（docs/22 §3.1 B）：连续失败达阈值后暂停判定，避免提供方宕机期间每轮都付超时
     */
    private boolean circuitBreakerEnabled = true;

    /**
     * 熔断触发阈值：连续失败次数达到该值即熔断
     */
    @Min(value = 1, message = "熔断阈值至少 1 次")
    @Max(value = 100, message = "熔断阈值过大")
    private int circuitBreakerThreshold = 3;

    /**
     * 熔断暂停时长（秒）：暂停期内一律不判定、不外呼
     */
    @Min(value = 1, message = "熔断暂停至少 1 秒")
    @Max(value = 86400, message = "熔断暂停过大")
    private int circuitBreakerPauseSeconds = 300;

    /**
     * 粘性路由开关（docs/22 §3.3 F）：追问场景**跳过判定**、直接复用上轮专家。
     *
     * <p>默认关闭（影子期先观察）。命中条件：本轮原话 ≤ {@link #stickyMaxQueryLength} 字，
     * 且上轮 {@code decision_source='J'} 且上轮判定置信度 ≥ {@link #stickyMinConfidence}，
     * 且上轮专家仍在**本轮候选**内（候选有效性校验）。命中不留判定、不外呼，来源记 {@code K}。</p>
     */
    private boolean stickyRoutingEnabled = false;

    /**
     * 粘性路由触发的上轮最低置信度（0~1）
     */
    @DecimalMin(value = "0.0", message = "粘性触发置信度不能小于 0")
    @DecimalMax(value = "1.0", message = "粘性触发置信度不能大于 1")
    private double stickyMinConfidence = 0.9;

    /**
     * 粘性路由触发的本轮最大字数（超出即走正常判定）
     */
    @Min(value = 1, message = "粘性触发字数至少 1")
    @Max(value = 200, message = "粘性触发字数过大")
    private int stickyMaxQueryLength = 12;

}