package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiChannelIdentity;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 渠道外部身份映射业务对象 ai_channel_identity
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChannelIdentity.class, reverseConvertGenerate = false)
public class AiChannelIdentityBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 所属渠道（ai_channel.id）
     */
    @NotNull(message = "所属渠道不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long channelId;

    /**
     * 外部用户标识（飞书 open_id / 企微 UserID / GitHub login）
     */
    @NotBlank(message = "外部用户标识不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 200, message = "外部用户标识不能超过 200 个字符", groups = {AddGroup.class, EditGroup.class})
    private String externalUserId;

    /**
     * 映射到的平台用户（sys_user.user_id）
     */
    @NotNull(message = "平台用户不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long platformUserId;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 列表查询用：按外部用户标识模糊匹配
     */
    private String keyword;

}