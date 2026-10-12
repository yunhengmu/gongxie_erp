package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结构化决策（专家路由判断）配置视图对象。
 *
 * <p>与 {@code AiJevConfigBo} 的业务字段一一对应，但<b>不含 {@code apiKey} 明文</b>：
 * 只回显 {@link #apiKeyConfigured}（是否已配置密钥），避免密钥下发前端。
 * 风格同 {@code AiModelConfigVo.apiKeyMask}——只暴露「是否已配置」这一事实。</p>
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
     * 决策模型服务地址（百炼 compatible-mode 基址）
     */
    private String baseUrl;

    /**
     * 决策模型标识（默认百炼决策模型；微调产物则填 {@code ...-ft-xxxxxxxx}）
     */
    private String model;

    /**
     * 外呼超时（秒）
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
     * 是否已配置访问密钥（不回显密钥明文本身）
     */
    private boolean apiKeyConfigured;

}