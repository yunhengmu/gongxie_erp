package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 渠道接入业务对象 ai_channel
 *
 * <p>凭据字段（{@code appSecret} / {@code encryptKey} / {@code verificationToken} /
 * {@code webhookSecret} / {@code apiToken}）为**明文入参**，由服务层加密后落库；
 * 编辑时留空表示「保持原值不变」，页面不回显明文。</p>
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChannel.class, reverseConvertGenerate = false)
public class AiChannelBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 渠道名称
     */
    @NotBlank(message = "渠道名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "渠道名称不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    private String channelName;

    /**
     * 渠道编码（唯一，回调地址中的路径标识）
     */
    @NotBlank(message = "渠道编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "渠道编码不能超过 64 个字符", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "渠道编码只能包含字母、数字、下划线与短横线",
        groups = {AddGroup.class, EditGroup.class})
    private String channelCode;

    /**
     * 平台类型（feishu / wecom / github）
     */
    @NotBlank(message = "平台类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String platformType;

    /**
     * 接入形态（webhook / ws）；留空默认 webhook。仅飞书区分 Webhook / 长连接，钉钉恒为长连接、不读本字段。
     */
    @Size(max = 16, message = "接入形态长度不能超过 16 个字符", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^(webhook|ws)?$", message = "接入形态只能为 webhook 或 ws", groups = {AddGroup.class, EditGroup.class})
    private String mode;

    /**
     * 默认智能体（ai_agent.id）
     */
    @NotNull(message = "默认智能体不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long agentId;

    /**
     * 应用标识（飞书 appId / 企微 corpId）
     */
    @Size(max = 200, message = "应用标识不能超过 200 个字符", groups = {AddGroup.class, EditGroup.class})
    private String appId;

    /**
     * 应用密钥（明文入参，留空表示保持原值）
     */
    private String appSecret;

    /**
     * 平台内应用 ID（企微 agentId）
     */
    @Size(max = 64, message = "平台内应用 ID 不能超过 64 个字符", groups = {AddGroup.class, EditGroup.class})
    private String platformAgentId;

    /**
     * 回调加解密密钥（明文入参，留空表示保持原值）
     */
    private String encryptKey;

    /**
     * 回调校验口令（明文入参，留空表示保持原值）
     */
    private String verificationToken;

    /**
     * Webhook 签名密钥（明文入参，留空表示保持原值）
     */
    private String webhookSecret;

    /**
     * 平台访问令牌（明文入参，留空表示保持原值）
     */
    private String apiToken;

    /**
     * 平台 API 基地址
     */
    @Size(max = 200, message = "API 基地址不能超过 200 个字符", groups = {AddGroup.class, EditGroup.class})
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
     * 列表查询用：按渠道名称 / 编码模糊匹配
     */
    private String keyword;

    /**
     * 列表查询用：平台类型
     */
    private String platformTypeQuery;

}