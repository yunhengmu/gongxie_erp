package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 渠道接入配置对象 ai_channel
 *
 * <p>一个渠道对应一个外部消息平台上的一个应用实例：
 * 入站回调经平台验签 / 解密后映射外部身份到平台账号，再把消息线程映射到本项目会话，
 * 走无人值守通道执行（写工具一律拒绝）。平台构件复用 AgentScope 的 channel 扩展。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_channel")
public class AiChannel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 平台类型：飞书
     */
    public static final String PLATFORM_FEISHU = "feishu";

    /**
     * 平台类型：企业微信
     */
    public static final String PLATFORM_WECOM = "wecom";

    /**
     * 平台类型：GitHub
     */
    public static final String PLATFORM_GITHUB = "github";

    /**
     * 平台类型：钉钉（长连接 / DingTalk Stream，无回调地址）
     */
    public static final String PLATFORM_DINGTALK = "dingtalk";

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 状态：停用
     */
    public static final String STATUS_DISABLED = "1";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 渠道名称
     */
    private String channelName;

    /**
     * 渠道编码（唯一，回调地址中的路径标识）
     */
    private String channelCode;

    /**
     * 平台类型（feishu / wecom / github / dingtalk）
     */
    private String platformType;

    /**
     * 默认智能体（ai_agent.id）
     */
    private Long agentId;

    /**
     * 应用标识（飞书 appId / 企微 corpId / 钉钉 AppKey；GitHub 为空）
     */
    private String appId;

    /**
     * 应用密钥（AES 密文；飞书 appSecret / 企微 secret / 钉钉 AppSecret）
     */
    private String appSecret;

    /**
     * 平台内应用 ID（企微 agentId / 钉钉 RobotCode；其它平台为空）
     */
    private String platformAgentId;

    /**
     * 回调加解密密钥（AES 密文；飞书 Encrypt Key / 企微 EncodingAESKey）
     */
    private String encryptKey;

    /**
     * 回调校验口令（AES 密文；飞书 Verification Token / 企微 Token）
     */
    private String verificationToken;

    /**
     * Webhook 签名密钥（AES 密文；GitHub Webhook Secret）
     */
    private String webhookSecret;

    /**
     * 平台访问令牌（AES 密文；GitHub PAT）
     */
    private String apiToken;

    /**
     * 平台 API 基地址（留空用各平台默认值）
     */
    private String apiBase;

    /**
     * 状态（0启用 1停用）
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