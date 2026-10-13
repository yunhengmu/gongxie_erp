package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结构化决策（专家路由判断）配置视图对象。
 *
 * <p>与 {@code AiJevConfigBo} 的业务字段一一对应。**不含任何连接信息**——决策模型的
 * 服务地址 / 模型标识 / 密钥统一由「模型配置」管理（{@code ai_model_config}，密钥密文落库、
 * 掩码回显），本对象只回带所选模型的 {@link #modelConfigId}；页面展示模型名称由前端用
 * 「模型配置」下拉的本地数据渲染。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiJevConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总开关：关闭＝零外呼、路由完全由小Z 负责
     */
    private boolean enabled;

    /**
     * 所选决策模型（「模型配置」主键，{@code model_type='decision'}）；{@code enabled=true} 时必填
     */
    private Long modelConfigId;

    /**
     * 判定超时（秒；与「模型配置」的通用超时无关——判定在路由关键路径上，需要更短的超时）
     */
    private int timeoutSeconds;

    /**
     * 执行模式：shadow 影子 / gate 闸门 / preselect 候选预筛
     */
    private String mode;

    /**
     * 闸门模式的置信度阈值（0~1）
     */
    private double confidenceThreshold;

    /**
     * 决策不可用时是否回落工具决策
     */
    private boolean failOpen;

    /**
     * 外呼 {@code state} 的字符上限（前置保护）
     */
    private int stateMaxChars;

    /**
     * 是否落库概率分布
     */
    private boolean persistProbabilities;

    /**
     * 是否启用熔断（docs/22 §3.1 B）
     */
    private boolean circuitBreakerEnabled;

    /**
     * 熔断触发阈值：连续失败次数
     */
    private int circuitBreakerThreshold;

    /**
     * 熔断暂停时长（秒）
     */
    private int circuitBreakerPauseSeconds;

}