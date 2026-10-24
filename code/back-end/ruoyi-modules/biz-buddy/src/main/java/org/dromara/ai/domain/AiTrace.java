package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 链路追踪对象 ai_trace（REQ-AI-28）
 *
 * <p>一次「任务」一条链路：实时对话一问一答（{@code source=chat}）、HITL 恢复段
 * （更新同一条，累加耗时与用量）、定时任务执行（{@code source=task}）。</p>
 *
 * <p>记录的是**执行当时**的模型版本与上下文清单（装配快照），因此事后修改智能体定义或
 * 运行期配置不会改写历史链路；工具明细与权限决策按 {@code session_id} / {@code message_id}
 * 关联既有审计表，本表不重复存储。</p>
 *
 * <p>追加型明细，物理删除，故不继承 {@code BaseEntity}（避免引入本表不存在的审计列）。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_trace")
public class AiTrace implements Serializable {

    /**
     * 状态：执行中
     */
    public static final String STATUS_RUNNING = "running";

    /**
     * 状态：等待人工确认（框架 HITL 暂停）
     */
    public static final String STATUS_PENDING_CONFIRM = "pending_confirm";

    /**
     * 状态：成功
     */
    public static final String STATUS_SUCCESS = "success";

    /**
     * 状态：失败
     */
    public static final String STATUS_FAILED = "failed";

    /**
     * 状态：中止（人工终止或恢复失败）
     */
    public static final String STATUS_ABORTED = "aborted";

    /**
     * 来源：实时对话
     */
    public static final String SOURCE_CHAT = "chat";

    /**
     * 来源：定时任务（无人值守）
     */
    public static final String SOURCE_TASK = "task";

    /**
     * 来源：渠道接入（无人值守）
     */
    public static final String SOURCE_CHANNEL = "channel";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话主键（ai_session.id）
     */
    private Long sessionId;

    /**
     * 助手消息主键（暂停阶段为待确认消息；收尾时回填）
     */
    private Long messageId;

    /**
     * 父链路（编排链路为父、每次专家调用为子；null 表示顶层；REQ-AI-30）
     */
    private Long parentTraceId;

    /**
     * 智能体主键（ai_agent.id）
     */
    private Long agentId;

    /**
     * 任务来源（chat / task）
     */
    private String source;

    /**
     * 触发用户（定时任务为运行身份用户）
     */
    private Long userId;

    /**
     * 主对话模型主键
     */
    private Long modelId;

    /**
     * 主对话模型标识（快照）
     */
    private String modelCode;

    /**
     * 主对话模型提供商（快照）
     */
    private String modelProvider;

    /**
     * 压缩摘要模型标识（快照，未配置为空）
     */
    private String summaryModelCode;

    /**
     * 记忆抽取与合并模型标识（快照，未配置为空）
     */
    private String memoryModelCode;

    /**
     * 子智能体默认模型标识（快照，未配置为空）
     */
    private String subagentModelCode;

    /**
     * 上下文清单 JSON
     */
    private String contextJson;

    /**
     * 状态迁移 JSON 数组
     */
    private String transitionsJson;

    /**
     * 链路状态
     */
    private String status;

    /**
     * 总耗时（毫秒，恢复段累加）
     */
    private Long durationMs;

    /**
     * 工具调用次数（恢复段累加）
     */
    private Integer toolCallCount;

    /**
     * 输入 Token（恢复段累加）
     */
    private Integer promptTokens;

    /**
     * 输出 Token（恢复段累加）
     */
    private Integer completionTokens;

    /**
     * 合计 Token（恢复段累加）
     */
    private Integer totalTokens;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}