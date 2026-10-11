package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 智能体定义视图对象**公共基类**（REQ-AI-33）。
 *
 * <p>拆表后本类不再映射任何实体（无 {@code @AutoMapper}），只承载两表共有字段 + 附加展示字段；
 * 具体视图为 {@link AiEntryAgentVo}（入口）与 {@link AiExpertAgentVo}（专家）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiAgentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体编码
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
     * 绑定对话模型主键
     */
    private Long modelId;

    /**
     * 长期记忆抽取 / 合并用的小模型
     */
    private Long memoryModelId;

    /**
     * 上下文压缩摘要模型
     */
    private Long summaryModelId;

    /**
     * 子智能体默认模型（子智能体未单独指定模型时生效）
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
     * 对话模型名称（附加字段，由 Service 填充，便于列表展示）
     */
    private String modelName;

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
     * 编排角色（entry 入口 / expert 专家；由 Service 按实体类型填充）
     */
    private String role;

    /**
     * 绑定的业务工具及授权决策（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<AiAgentToolVo> toolBindings;

    /**
     * 绑定的知识库主键集合（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<Long> kbIds;

    /**
     * 绑定的技能主键集合（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private List<Long> skillIds;

    /**
     * 绑定的子智能体主键集合（附加字段，由 Service 填充，REQ-AI-04）
     */
    @TableField(exist = false)
    private List<Long> subagentIds;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
