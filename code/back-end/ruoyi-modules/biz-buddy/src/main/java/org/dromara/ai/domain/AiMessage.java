package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 会话消息对象 ai_message
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_message")
public class AiMessage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话消息角色：用户
     */
    public static final String ROLE_USER = "user";

    /**
     * 会话消息角色：助手
     */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 消息状态：正常结束
     */
    public static final String STATUS_NORMAL = "0";

    /**
     * 消息状态：等待人工确认工具调用（HITL 暂停）
     */
    public static final String STATUS_PENDING_CONFIRM = "2";

    /**
     * 消息状态：工具调用被拒绝或确认超时，本轮未完成
     */
    public static final String STATUS_ABORTED = "3";

    /**
     * 主键
     */
    @TableId(value = "id")
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
     * 回答参考来源（JSON 字符串，知识库命中；可空）
     */
    private String sources;

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
