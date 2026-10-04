package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSubagent;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 子智能体视图对象 ai_subagent（REQ-AI-04）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSubagent.class)
public class AiSubagentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 子智能体标识（即 AgentScope 的 agent_id）
     */
    private String name;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 子智能体系统提示词
     */
    private String sysPrompt;

    /**
     * 工作区模式（ISOLATED 隔离 / SHARED 共享）
     */
    private String workspaceMode;

    /**
     * 指定模型主键（可空）
     */
    private Long modelId;

    /**
     * 指定模型名称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String modelName;

    /**
     * 单次最大推理迭代次数
     */
    private Integer steps;

    /**
     * 工具白名单（落库的 JSON 数组文本，与实体字段同名以便转换）
     */
    private String toolCodes;

    /**
     * 工具白名单（附加字段，由 Service 从 toolCodes JSON 解析）
     */
    @TableField(exist = false)
    private List<String> toolCodeList;

    /**
     * 工具白名单显示名（附加字段，由 Service 按工具编码填充）
     */
    @TableField(exist = false)
    private List<String> toolNames;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建人
     */
    private Long createBy;

    /**
     * 创建人昵称（附加字段，由 Service 填充）
     */
    @TableField(exist = false)
    private String createByName;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}