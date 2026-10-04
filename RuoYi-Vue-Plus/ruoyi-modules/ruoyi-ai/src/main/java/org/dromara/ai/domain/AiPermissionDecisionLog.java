package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 工具权限决策日志对象 ai_permission_decision_log
 *
 * <p>追加型明细，物理删除，故不继承 BaseEntity（表中仅保留 create_time）。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_permission_decision_log")
public class AiPermissionDecisionLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 决策：允许
     */
    public static final String DECISION_ALLOW = "0";

    /**
     * 决策：需人工确认
     */
    public static final String DECISION_ASK = "1";

    /**
     * 决策来源：智能体 × 工具的显式策略
     */
    public static final String SOURCE_AGENT_TOOL = "agent_tool";

    /**
     * 决策来源：用户选择「记住该工具」
     */
    public static final String SOURCE_REMEMBER = "remember";

    /**
     * 确认结果：拒绝
     */
    public static final String CONFIRM_REJECTED = "0";

    /**
     * 确认结果：确认
     */
    public static final String CONFIRM_APPROVED = "1";

    /**
     * 确认作用范围：仅本次
     */
    public static final String CONFIRM_ONCE = "once";

    /**
     * 确认作用范围：记住该工具
     */
    public static final String CONFIRM_REMEMBER = "remember";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属会话
     */
    private Long sessionId;

    /**
     * 所属助手消息
     */
    private Long messageId;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 触发用户
     */
    private Long userId;

    /**
     * 工具编码
     */
    private String toolCode;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 决策（0允许 1需确认 2拒绝）
     */
    private String decision;

    /**
     * 决策来源（agent_tool / remember / global_mode / fallback）
     */
    private String decisionSource;

    /**
     * 命中的规则名
     */
    private String matchedRule;

    /**
     * 决策说明
     */
    private String reason;

    /**
     * 工具入参 JSON
     */
    private String toolInput;

    /**
     * 人工确认结果（0拒绝 1确认；非 ASK 决策为空）
     */
    private String confirmed;

    /**
     * 确认作用范围（once / remember）
     */
    private String confirmSource;

    /**
     * 工具执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}