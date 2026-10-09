package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 专家提示词辅助生成入参（REQ-AI-33 补强）。
 *
 * <p>定义页「快速生成」只收集少量硬信息，由模型补全「描述 / 系统提示词」两项。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiExpertPromptBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 专家名称
     */
    private String agentName;

    /**
     * 领域一句话（用来说明该专家负责什么）
     */
    private String domainHint;

    /**
     * 将绑定的业务工具显示名（含只读工具）
     */
    private List<String> toolNames;

    /**
     * 其中属于写操作的工具显示名
     */
    private List<String> writeToolNames;

    /**
     * 可检索的知识库名称
     */
    private List<String> kbNames;

    /**
     * 是否允许写操作
     */
    private Boolean writeEnabled;

    /**
     * 用于本次生成的对话模型主键；为空时取首个启用的对话模型
     */
    private Long modelId;

}