package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 渠道外部身份映射对象 ai_channel_identity
 *
 * <p>把外部平台的用户标识（飞书 open_id / 企微 UserID / GitHub login）映射到平台账号。
 * <b>未映射的外部身份一律拒绝执行</b>（不回退到任何默认运行身份）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_channel_identity")
public class AiChannelIdentity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * 所属渠道（ai_channel.id）
     */
    private Long channelId;

    /**
     * 外部用户标识（飞书 open_id / 企微 UserID / GitHub login）
     */
    private String externalUserId;

    /**
     * 映射到的平台用户（sys_user.user_id）
     */
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
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}