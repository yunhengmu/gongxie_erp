package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 子智能体业务对象 ai_subagent（REQ-AI-04）
 *
 * <p>工具白名单以 {@code toolCodeList} 数组形式收发（落库为 JSON 文本），
 * 服务层强制校验「只允许只读工具」——子智能体侧没有人工确认闸门，写入类工具不得下放。</p>
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSubagent.class, reverseConvertGenerate = false)
public class AiSubagentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 子智能体标识（唯一，即 AgentScope 的 agent_id）
     */
    @NotBlank(message = "子智能体标识不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "子智能体标识不能超过 100 个字符", groups = {AddGroup.class, EditGroup.class})
    @Pattern(regexp = "^[a-z][a-z0-9_-]{1,60}$",
        message = "子智能体标识只能包含小写字母、数字、下划线或短横线，且以字母开头（2~61 位）",
        groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 用途说明（主智能体据此决定是否委派）
     */
    @NotBlank(message = "用途说明不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 1000, message = "用途说明不能超过 1000 个字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 子智能体系统提示词（可空；为空时使用框架通用子智能体提示词）
     */
    private String sysPrompt;

    /**
     * 工作区模式（ISOLATED 隔离 / SHARED 共享；为空按隔离处理）
     */
    private String workspaceMode;

    /**
     * 指定模型（ai_model_config.id，可空：为空时用智能体的子智能体默认模型，再为空则继承主模型）
     */
    private Long modelId;

    /**
     * 单次最大推理迭代次数
     */
    @Min(value = 1, message = "步数不能小于 1", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 100, message = "步数不能大于 100", groups = {AddGroup.class, EditGroup.class})
    private Integer steps;

    /**
     * 工具白名单（仅允许只读工具；为空表示不下放任何业务工具）
     */
    private List<String> toolCodeList;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 列表查询用：按标识或用途说明模糊匹配
     */
    private String keyword;

}