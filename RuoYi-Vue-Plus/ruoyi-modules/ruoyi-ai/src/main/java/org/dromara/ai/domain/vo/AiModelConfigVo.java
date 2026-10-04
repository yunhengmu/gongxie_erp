package org.dromara.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiModelConfig;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型配置视图对象 ai_model_config
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiModelConfig.class)
public class AiModelConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * 提供商
     */
    private String provider;

    /**
     * 模型类型（chat 对话 / embedding 向量）
     */
    private String modelType;

    /**
     * 模型标识
     */
    private String modelCode;

    /**
     * 服务地址
     */
    private String baseUrl;

    /**
     * API 密钥密文：仅用于服务端计算掩码，不参与序列化，绝不下发前端
     */
    @JsonIgnore
    private String apiKey;

    /**
     * 密钥掩码（已配置为 ******，未配置为空串）
     */
    private String apiKeyMask;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds;

    /**
     * 失败重试次数
     */
    private Integer maxRetries;

    /**
     * 输入单价（元 / 千 Token），为空表示未配置
     */
    private BigDecimal inputPrice;

    /**
     * 输出单价（元 / 千 Token），为空表示未配置
     */
    private BigDecimal outputPrice;

    /**
     * 状态（0正常 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}