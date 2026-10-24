package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSession;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话视图对象 ai_session
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSession.class)
public class AiSessionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话主键（即 AgentScope 的 sessionId）
     */
    private Long id;

    /**
     * 会话标题
     */
    private String sessionTitle;

    /**
     * 所属智能体主键
     */
    private Long agentId;

    /**
     * 智能体编码快照
     */
    private String agentCode;

    /**
     * 智能体名称（附加字段，由 Service 填充，供会话管理页展示）
     */
    @TableField(exist = false)
    private String agentName;

    /**
     * 所选场景包主键（可空）
     */
    private Long scenarioPackId;

    /**
     * 所选场景包编码快照（可空）
     */
    private String scenarioPackCode;

    /**
     * 所选场景包名称（附加字段，由 Service 填充，供会话管理页展示）
     */
    @TableField(exist = false)
    private String scenarioPackName;

    /**
     * 归属用户 ID
     */
    private Long userId;

    /**
     * 归属用户昵称（附加字段，由 Service 填充，供会话管理页展示）
     */
    @TableField(exist = false)
    private String userName;

    /**
     * 归属部门 ID
     */
    private Long deptId;

    /**
     * 归属部门名称（附加字段，由 Service 填充，供会话管理页展示）
     */
    @TableField(exist = false)
    private String deptName;

    /**
     * 消息条数
     */
    private Integer messageCount;

    /**
     * 状态（0进行中 1已结束）
     */
    private String status;

    /**
     * 置顶标志（0普通 1置顶）
     */
    private String topFlag;

    /**
     * 最后活跃时间
     */
    private LocalDateTime lastActiveTime;

    /**
     * 会话来源（chat 手动对话 / task 定时任务 / channel 渠道接入）
     */
    private String source;

    /**
     * 指定专家主键（可空；REQ-AI-31）
     */
    private Long pinnedExpertId;

    /**
     * 指定专家名称（附加字段，由 Service 填充，供对话页会话头展示「已指定：X」）
     */
    @TableField(exist = false)
    private String pinnedExpertName;

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
