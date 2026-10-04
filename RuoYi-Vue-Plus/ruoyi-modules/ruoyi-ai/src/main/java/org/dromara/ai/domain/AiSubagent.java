package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 子智能体定义对象 ai_subagent（REQ-AI-04）
 *
 * <p>子智能体由 AgentScope 的 {@code SubagentDeclaration} 承载：{@code name} 即
 * {@code agent_id}，主智能体通过框架内置的 {@code agent_spawn} 工具把任务委派给它。
 * 本表为**平台级可复用定义**，与智能体为多对多绑定（{@code ai_agent_subagent}）。</p>
 *
 * <p>{@code toolCodes} 以 JSON 数组文本落库（列 {@code tool_codes}），业务对象与视图对象
 * 均以 {@code toolCodeList} 暴露数组形式；服务层负责翻译与校验（**只允许只读工具**）。</p>
 *
 * <p>删除为物理删除（不设 {@code del_flag}）：唯一索引 {@code uk_ai_subagent_name}
 * 需支持删除后复用同名标识，而逻辑删除行会占用该唯一索引。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_subagent")
public class AiSubagent extends BaseEntity {

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 工作区模式：隔离（子智能体有独立工作区，状态按「父会话 × 用户」分桶）
     */
    public static final String WORKSPACE_ISOLATED = "ISOLATED";

    /**
     * 工作区模式：共享（子智能体直接使用主智能体工作区）
     */
    public static final String WORKSPACE_SHARED = "SHARED";

    /**
     * 单次最大推理迭代次数的框架默认值
     */
    public static final int DEFAULT_STEPS = 10;

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 子智能体标识（唯一，即 AgentScope 的 agent_id）
     */
    private String name;

    /**
     * 用途说明（主智能体据此决定是否委派）
     */
    private String description;

    /**
     * 子智能体系统提示词（即框架的 inlineAgentsBody）
     */
    private String sysPrompt;

    /**
     * 工作区模式（ISOLATED 隔离 / SHARED 共享）
     */
    private String workspaceMode;

    /**
     * 指定模型（ai_model_config.id，可空）
     */
    private Long modelId;

    /**
     * 单次最大推理迭代次数
     */
    private Integer steps;

    /**
     * 工具白名单（JSON 数组文本，仅允许只读工具）
     */
    private String toolCodes;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

}