package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiChannelThread;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 渠道消息线程与会话映射视图对象 ai_channel_thread
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChannelThread.class)
public class AiChannelThreadVo implements Serializable {

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
     * 线程标识
     */
    private String threadKey;

    /**
     * 映射到的会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 会话标题（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String sessionTitle;

    /**
     * 会话状态（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String sessionStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后活跃时间
     */
    private LocalDateTime lastActiveTime;

}