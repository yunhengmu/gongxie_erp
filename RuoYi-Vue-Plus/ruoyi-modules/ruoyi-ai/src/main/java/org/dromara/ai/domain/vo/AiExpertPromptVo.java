package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 专家提示词辅助生成结果。
 *
 * <p>两项均为**建议值**，前端回填后仍可编辑；生成失败时 {@code success=false}、{@code message} 给出原因。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiExpertPromptVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否生成成功
     */
    private Boolean success;

    /**
     * 结果说明（成功为提示，失败为错误原因）
     */
    private String message;

    /**
     * 一句话职责说明（建议值；同时作为入口智能体候选清单里的「擅长」，为空时回落该字段）
     */
    private String description;

    /**
     * 系统提示词（建议值，仅承载领域知识与口径）
     */
    private String sysPrompt;

    /**
     * 实际使用的对话模型名称
     */
    private String modelName;

    /**
     * 耗时（毫秒）
     */
    private Long elapsedMs;

}