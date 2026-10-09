package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 智能体定义业务对象**公共基类**（REQ-AI-33）。
 *
 * <p>拆表后本类不再映射任何实体（无 {@code @AutoMapper}），只承载两表共有字段；
 * 具体业务对象为 {@link AiEntryAgentBo}（入口）与 {@link AiExpertAgentBo}（专家）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiAgentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 智能体名称
     */
    @NotBlank(message = "智能体名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String agentName;

    /**
     * 智能体编码（唯一）
     */
    @NotBlank(message = "智能体编码不能为空", groups = {AddGroup.class, EditGroup.class})
    private String agentCode;

    /**
     * 一句话职责说明
     */
    private String description;

    /**
     * 人格提示词（留空时后端落默认提示词）
     */
    private String sysPrompt;

    /**
     * 绑定对话模型（ai_model_config.id，model_type=chat）
     */
    @NotNull(message = "绑定对话模型不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long modelId;

    /**
     * 长期记忆抽取 / 合并用的小模型（可空；待 2D 生效）
     */
    private Long memoryModelId;

    /**
     * 上下文压缩摘要模型（可空；待 2C 生效）
     */
    private Long summaryModelId;

    /**
     * 子智能体默认模型（可空；待 P3 生效）
     */
    private Long subagentModelId;

    /**
     * 日 Token 配额（0 表示不限）
     */
    @Min(value = 0, message = "日 Token 配额不能为负数", groups = {AddGroup.class, EditGroup.class})
    private Long dailyTokenQuota;

    /**
     * 日请求数配额（0 表示不限）
     */
    @Min(value = 0, message = "日请求数配额不能为负数", groups = {AddGroup.class, EditGroup.class})
    private Integer dailyRequestQuota;

    /**
     * 月 Token 配额（0 表示不限）
     */
    @Min(value = 0, message = "月 Token 配额不能为负数", groups = {AddGroup.class, EditGroup.class})
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
    @Min(value = 1, message = "最大迭代次数不能小于 1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 50, message = "最大迭代次数不能大于 50", groups = {AddGroup.class, EditGroup.class})
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
     * 绑定的业务工具及授权决策
     */
    private List<AiAgentToolBo> toolBindings;

    /**
     * 绑定的知识库主键集合
     */
    private List<Long> kbIds;

    /**
     * 绑定的技能主键集合（REQ-AI-10）
     */
    private List<Long> skillIds;

    /**
     * 绑定的子智能体主键集合（REQ-AI-04）
     */
    private List<Long> subagentIds;

    /**
     * 列表查询用：按名称模糊匹配
     */
    private String nameKeyword;

}
