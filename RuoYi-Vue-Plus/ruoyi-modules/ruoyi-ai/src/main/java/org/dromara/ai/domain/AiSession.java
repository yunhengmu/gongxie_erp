package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * AI 会话对象 ai_session
 *
 * <p>id 由业务侧生成（雪花）并持久化，同时作为 AgentScope 的 sessionId 使用，
 * 不使用连接 ID，断线后可按同一 sessionId 续接同一段记忆。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_session")
public class AiSession extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 置顶标志：普通
     */
    public static final String TOP_NORMAL = "0";

    /**
     * 置顶标志：置顶
     */
    public static final String TOP_PINNED = "1";

    /**
     * 会话来源：手动对话（对话工作台）
     */
    public static final String SOURCE_CHAT = "chat";

    /**
     * 会话来源：定时任务（无人值守执行）
     */
    public static final String SOURCE_TASK = "task";

    /**
     * 会话来源：渠道接入（外部消息平台入站，无人值守执行）
     */
    public static final String SOURCE_CHANNEL = "channel";

    /**
     * 主键（即 AgentScope 的 sessionId）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话标题
     */
    private String sessionTitle;

    /**
     * 所属智能体（ai_agent.id）
     */
    private Long agentId;

    /**
     * 智能体编码快照
     */
    private String agentCode;

    /**
     * 所选场景包（ai_scenario_pack.id，可空）
     *
     * <p>创建会话时选定并固化，**会话内不可更换**；为空时该会话不接入任何 MCP 工具，
     * 也不叠加包内工具与技能（与既定口径一致：权限上下文按 (userId, sessionId) 槽位生效）。</p>
     */
    private Long scenarioPackId;

    /**
     * 场景包编码快照（与 agent_code 同款，避免展示时回查）
     */
    private String scenarioPackCode;

    /**
     * 归属用户 ID
     */
    private Long userId;

    /**
     * 归属部门 ID
     */
    private Long deptId;

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
     * 指定专家（ai_agent.id，可空；REQ-AI-31）
     *
     * <p>非空＝本会话本轮**强制路由**到该专家（用户指定，落 {@code ai_route_run.action=pinned}）；
     * 为空＝走入口智能体（小Z）的正常决策。与场景包不同，**会话内可变更**（取消指定即置空），
     * 会话发言人恒为小Z、上下文不因指定 / 取消而中断。</p>
     */
    private Long pinnedExpertId;

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
