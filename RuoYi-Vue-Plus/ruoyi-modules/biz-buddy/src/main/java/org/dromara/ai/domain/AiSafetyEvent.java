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
 * AI 安全事件对象 ai_safety_event
 *
 * <p>敏感词 / PII 命中与处置的追加型明细，物理删除，故不继承 BaseEntity（表中仅保留 create_time）。</p>
 *
 * @author BizBuddy
 */
@Data
@TableName("ai_safety_event")
public class AiSafetyEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 场景（input 用户输入 / output 模型输出 / channel_out 渠道出站）
     */
    private String scene;

    /**
     * 处置动作（log 只记录 / mask 脱敏放行 / block 阻断）
     */
    private String action;

    /**
     * 命中引擎（word 敏感词 / pii 隐私信息）
     */
    private String engine;

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
     * 命中项数量
     */
    private Integer hitCount;

    /**
     * 命中项 JSON 数组（命中词 / PII 类型）
     */
    private String hitsJson;

    /**
     * 触发文本摘要（截断至 200 字符）
     */
    private String textExcerpt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}