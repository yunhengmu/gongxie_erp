package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiChannelIdentity;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 渠道外部身份映射视图对象 ai_channel_identity
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChannelIdentity.class)
public class AiChannelIdentityVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属渠道（ai_channel.id）
     */
    private Long channelId;

    /**
     * 所属渠道名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String channelName;

    /**
     * 外部用户标识
     */
    private String externalUserId;

    /**
     * 映射到的平台用户
     */
    private Long platformUserId;

    /**
     * 平台用户昵称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String platformUserName;

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