package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiTrace;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 链路追踪视图对象 ai_trace（REQ-AI-28）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiTrace.class)
public class AiTraceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 会话主键
     */
    private Long sessionId;

    /**
     * 助手消息主键
     */
    private Long messageId;

    /**
     * 父链路主键（编排链路为父、专家调用为子；null 表示顶层；REQ-AI-30）
     */
    private Long parentTraceId;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 智能体名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String agentName;

    /**
     * 智能体编码（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String agentCode;

    /**
     * 任务来源（chat / task）
     */
    private String source;

    /**
     * 触发用户
     */
    private Long userId;

    /**
     * 触发用户昵称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String userName;

    /**
     * 会话标题（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String sessionTitle;

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
     * 压缩摘要模型标识（快照）
     */
    private String summaryModelCode;

    /**
     * 记忆抽取与合并模型标识（快照）
     */
    private String memoryModelCode;

    /**
     * 子智能体默认模型标识（快照）
     */
    private String subagentModelCode;

    /**
     * 上下文清单 JSON（落库值）
     */
    private String contextJson;

    /**
     * 上下文清单（附加字段，由 Service 解析）
     */
    @TableField(exist = false)
    private Map<String, Object> context;

    /**
     * 状态迁移 JSON（落库值）
     */
    private String transitionsJson;

    /**
     * 状态迁移时间线（附加字段，由 Service 解析）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> transitions;

    /**
     * 链路状态
     */
    private String status;

    /**
     * 总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 工具调用次数
     */
    private Integer toolCallCount;

    /**
     * 输入 Token
     */
    private Integer promptTokens;

    /**
     * 输出 Token
     */
    private Integer completionTokens;

    /**
     * 合计 Token
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