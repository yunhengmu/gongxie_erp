package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.ai.domain.AiModelConfig;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 模型配置业务对象 ai_model_config
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiModelConfig.class, reverseConvertGenerate = false)
public class AiModelConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 配置名称
     */
    @NotBlank(message = "配置名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String configName;

    /**
     * 提供商
     */
    @NotBlank(message = "提供商不能为空", groups = {AddGroup.class, EditGroup.class})
    private String provider;

    /**
     * 模型类型（chat 对话 / embedding 向量 / decision 决策）
     */
    @NotBlank(message = "模型类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String modelType;

    /**
     * 模型标识
     */
    @NotBlank(message = "模型标识不能为空", groups = {AddGroup.class, EditGroup.class})
    private String modelCode;

    /**
     * 是否支持视觉（图片输入；仅对话模型有意义）：1是 0否
     */
    private String supportsVision;

    /**
     * 服务地址（OpenAI 兼容根地址）
     */
    @NotBlank(message = "服务地址不能为空", groups = {AddGroup.class, EditGroup.class})
    private String baseUrl;

    /**
     * API 密钥明文；新增时可空（本地 Ollama 无需密钥），修改时留空表示不修改
     */
    private String apiKey;

    /**
     * 向量维度（embedding 模型必填）
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
     * 输入单价（元 / 千 Token），为空按 0 计
     */
    private BigDecimal inputPrice;

    /**
     * 输出单价（元 / 千 Token），为空按 0 计
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

}