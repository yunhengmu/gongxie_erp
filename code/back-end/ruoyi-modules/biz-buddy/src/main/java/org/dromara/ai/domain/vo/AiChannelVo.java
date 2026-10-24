package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiChannel;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 渠道接入视图对象 ai_channel
 *
 * <p>凭据列**不回显**，仅以 {@code *Set} 布尔位告知页面「是否已配置」，避免明文外泄。</p>
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChannel.class)
public class AiChannelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 渠道名称
     */
    private String channelName;

    /**
     * 渠道编码
     */
    private String channelCode;

    /**
     * 平台类型（feishu / wecom / github）
     */
    private String platformType;

    /**
     * 接入形态（webhook / ws）
     */
    private String mode;

    /**
     * 默认智能体主键
     */
    private Long agentId;

    /**
     * 默认智能体名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String agentName;

    /**
     * 默认智能体状态（附加字段，由 Service 填充；1 表示已停用，渠道将无法执行）
     */
    @TableField(exist = false)
    private String agentStatus;

    /**
     * 应用标识
     */
    private String appId;

    /**
     * 平台内应用 ID
     */
    private String platformAgentId;

    /**
     * 平台 API 基地址
     */
    private String apiBase;

    /**
     * 是否已配置应用密钥（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Boolean appSecretSet;

    /**
     * 是否已配置回调加解密密钥（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Boolean encryptKeySet;

    /**
     * 是否已配置回调校验口令（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Boolean verificationTokenSet;

    /**
     * 是否已配置 Webhook 签名密钥（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Boolean webhookSecretSet;

    /**
     * 是否已配置平台访问令牌（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private Boolean apiTokenSet;

    /**
     * 长连接渠道（钉钉）的运行状态（附加字段，由 Service 填充；stopped / running / failed）
     */
    @TableField(exist = false)
    private String streamStatus;

    /**
     * 长连接启动失败原因（附加字段，由 Service 填充；仅 failed 时有值）
     */
    @TableField(exist = false)
    private String streamStatusDetail;

    /**
     * 状态（0启用 1停用）
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