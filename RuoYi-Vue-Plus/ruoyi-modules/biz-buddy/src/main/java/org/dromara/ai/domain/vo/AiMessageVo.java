package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiMessage;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话消息视图对象 ai_message
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiMessage.class)
public class AiMessageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属会话（ai_session.id）
     */
    private Long sessionId;

    /**
     * 角色（user / assistant / system）
     */
    private String role;

    /**
     * 消息正文
     */
    private String content;

    /**
     * AgentScope replyId，用于聚合流式块
     */
    private String replyId;

    /**
     * 流式状态（0已完成 1流式中）
     */
    private String streaming;

    /**
     * 状态（0正常 1失败）
     */
    private String status;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 本轮工具调用次数
     */
    private Integer toolCallCount;

    /**
     * 输入 Token 数
     */
    private Integer promptTokens;

    /**
     * 输出 Token 数
     */
    private Integer completionTokens;

    /**
     * 总 Token 数
     */
    private Integer totalTokens;

    /**
     * 本轮耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 回答参考来源（JSON 字符串，前端自行解析；可空）
     */
    private String sources;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 随本条消息发送的附件（聊天上传的图片 / 文档；非消息表字段，由服务层填充）
     */
    private List<AiChatAttachmentVo> attachments;

}
