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
    private int timeoutSeconds = 5;

    /**
     * 执行模式：shadow 影子（只留痕不接管）/ gate 闸门（高置信接管）/ preselect 候选预筛（可选）
     */
    @Pattern(regexp = "^(shadow|gate|preselect)$", message = "执行模式只能是 shadow / gate / preselect")
    private String mode = "shadow";

    /**
     * 闸门模式的置信度阈值（0~1）
     */
    @DecimalMin(value = "0.0", message = "置信度阈值不能小于 0")
    @DecimalMax(value = "1.0", message = "置信度阈值不能大于 1")
    private double confidenceThreshold = 0.75;

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

}