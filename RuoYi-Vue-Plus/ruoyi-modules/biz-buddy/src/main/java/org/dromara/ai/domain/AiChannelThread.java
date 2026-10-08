package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 渠道消息线程与会话映射对象 ai_channel_thread
 *
 * <p>同一线程（飞书 chat_id / 企微 UserID / GitHub owner/repo#number）的后续消息
 * 续接同一条 {@code ai_session.id}，实现「消息线程 → 会话」的映射。
 * 追加型物理表，不设 {@code del_flag}。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_channel_thread")
public class AiChannelThread implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

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
     * 线程标识（含 peer 类型前缀，如 {@code DIRECT:oc_xxx}）
     */
    private String threadKey;

    /**
     * 映射到的会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后活跃时间
     */
    private LocalDateTime lastActiveTime;

}