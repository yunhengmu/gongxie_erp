package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体定义**公共基类**（REQ-AI-33）。
 *
 * <p>编排角色由三值精简为**两值（入口 / 专家）**后，`ai_agent` 单表拆为两张物理表：
 * {@link AiEntryAgent}（入口，全平台唯一 1 行＝小Z）与 {@link AiExpertAgent}（专家，多行）。
 * 本类**不映射任何表**（无 {@code @TableName}），只承载两表共有的字段，
 * 以及供装配 / 编排链路复用的「角色派生」方法——原先靠 {@code agent_role} 列判定，
 * 现在改为靠**具体实体类型**判定（见 {@link #isEntry()}）。</p>
 *
 * <p>被取消的「业务智能体」角色（原 {@code agent_role='0'}）不复存在：这类智能体已删除；
 * 若需要「只能被用户手动指定、不进自动候选」的智能体，用专家 + {@code auto_route='0'} 表达。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AiAgent extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 编排角色标识：入口（管理面 VO 的 {@code role} 取值；路由决策的发起方，全平台唯一）
     */
    public static final String ROLE_ENTRY = "entry";

    /**
     * 编排角色标识：专家（管理面 VO 的 {@code role} 取值；可被入口智能体路由的候选）
     */
    public static final String ROLE_EXPERT = "expert";

    /**
     * 专家写工具：允许（按「允许」档生效；「需确认」档走 HITL 确认卡片）
     */
    public static final String WRITE_ENABLED = "1";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体编码（唯一，作为 AgentScope 的 agent name）
     */
    private String agentCode;

    /**
     * 一句话职责说明
     */
    private String description;

    /**
     * 人格提示词（系统提示词）
     */
    private String sysPrompt;

    /**
     * 绑定对话模型（ai_model_config.id，model_type=chat）
     */
    private Long modelId;

    /**
     * 长期记忆抽取 / 合并用的小模型（待 2D 生效）
     */
    private Long memoryModelId;

    /**
     * 上下文压缩摘要模型（待 2C 生效）
     */
    private Long summaryModelId;

    /**
     * 子智能体默认模型（REQ-AI-04；子智能体未单独指定模型时使用，仍为空则继承主模型）
     */
    private Long subagentModelId;

    /**
     * 日 Token 配额（0 表示不限）
     */
    private Long dailyTokenQuota;

    /**
     * 日请求数配额（0 表示不限）
     */
    private Integer dailyRequestQuota;

    /**
     * 月 Token 配额（0 表示不限）
     */
    private Long monthlyTokenQuota;

    /**
     * 归属部门 ID
     */
    private Long ownerDeptId;

    /**
     * 可见范围（0全员 1本部门 2仅创建人）
     */
    private String visibleScope;

    /**
     * 单轮最大迭代次数
     */
    private Integer maxIters;

    /**
     * 状态（0正常 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 是否为入口智能体（装配与编排链路的角色判定入口）。
     *
     * @return 入口返回 true；基类默认 false（专家）
     */
    public boolean isEntry() {
        return false;
    }

    /**
     * 是否允许专家使用写工具（装配层「专家写工具条件求交」用）。
     *
     * @return 基类默认 false；仅 {@link AiExpertAgent} 按自己的开关覆写
     */
    public boolean expertWriteAllowed() {
        return false;
    }

    /**
     * 编排角色标识（供管理面 VO 填充与前端判定）。
     *
     * @return {@link #ROLE_ENTRY} 或 {@link #ROLE_EXPERT}
     */
    public String role() {
        return isEntry() ? ROLE_ENTRY : ROLE_EXPERT;
    }

}
