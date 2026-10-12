package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * AI 模型配置对象 ai_model_config
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_model_config")
public class AiModelConfig extends BaseEntity {

    /**
     * 是否支持视觉（图片输入）：支持
     */
    public static final String VISION_SUPPORTED = "1";

    /**
     * 是否支持视觉（图片输入）：不支持
     */
    public static final String VISION_UNSUPPORTED = "0";

    /**
     * 模型类型：对话模型
     */
    public static final String TYPE_CHAT = "chat";

    /**
     * 模型类型：向量模型
     */
    public static final String TYPE_EMBEDDING = "embedding";

    /**
     * 模型类型：决策模型（结构化决策 / 专家路由判断，TypeSafe System One 协议，见 docs/21）
     */
    public static final String TYPE_DECISION = "decision";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
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
     * 模型类型（chat 对话 / embedding 向量 / decision 决策）
     */
    private String modelType;

    /**
     * 模型标识
     */
    private String modelCode;

    /**
     * 是否支持视觉（图片输入；仅对话模型有意义）：1是 0否
     *
     * <p>聊天上传图片走 AgentScope {@code ImageBlock}（OpenAI {@code image_url}），**非视觉模型**
     * 会报错或被忽略；本列用于上传前的能力校验，避免「白屏式」的模型侧报错（见 docs/14 第 82 项）。</p>
     */
    private String supportsVision;

    /**
     * 服务地址（OpenAI 兼容根地址）
     */
    private String baseUrl;

    /**
     * API 密钥（AES 密文）
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
     * 输入单价（元 / 千 Token），用于用量费用估算；为空按 0 计
     */
    private BigDecimal inputPrice;

    /**
     * 输出单价（元 / 千 Token），用于用量费用估算；为空按 0 计
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
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}