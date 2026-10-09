package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiExpertPromptBo;
import org.dromara.ai.domain.vo.AiExpertPromptVo;

/**
 * 专家提示词辅助生成 Service 接口（REQ-AI-33 补强）。
 *
 * <p>专家行为纪律由平台强制注入，{@code sysPrompt} 只需承载「领域知识与口径」；
 * 本服务据此用一次对话模型调用，按少量硬信息补全「描述 / 系统提示词」两项建议值。</p>
 *
 * @author BizBuddy
 */
public interface IAiExpertPromptService {

    /**
     * 按少量硬信息生成专家配置建议值。
     *
     * @param bo 入参（名称 / 领域一句话 / 绑定工具 / 知识库 / 是否允许写操作 / 生成用模型）
     * @return 生成结果（含 {@code success} 与失败原因）
     */
    AiExpertPromptVo generate(AiExpertPromptBo bo);

}