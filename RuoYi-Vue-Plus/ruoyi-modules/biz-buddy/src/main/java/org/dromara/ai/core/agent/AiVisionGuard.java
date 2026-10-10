package org.dromara.ai.core.agent;

import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片附件的「视觉能力声明」校验（多模态）。
 *
 * <p>聊天上传的图片走 AgentScope {@code ImageBlock}（OpenAI {@code image_url} 的 Base64 data URI），
 * **非视觉模型**会直接报错或被静默忽略——线上表现为「白屏式」失败，用户看不出原因。故在图片附件
 * **登记（上传）时**先按 {@code ai_model_config.supports_vision} 校验，把失败提前成一句可读的中文提示。</p>
 *
 * <p><b>校验范围＝本轮实际会收到图片消息的模型</b>：入口智能体（会话所属智能体）恒是第一个消费者；
 * 本会话若「指定专家」（REQ-AI-31），编排也会把图片内容块一并下发给该专家，故一并校验。
 * 自动路由到的专家无法在发送前预知，**不纳入**前置校验（见 docs/14 第 82 项）。</p>
 *
 * <p><b>只拦「显式声明不支持」的模型</b>：{@code supports_vision='0'} 才拒绝；未配置 / 存量空值
 * 一律按支持处理（与列默认值「1」同口径），避免因未回填而误挡正常用户。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AiVisionGuard {

    /**
     * 指定专家的提示前缀（用于错误文案区分两个校验对象）
     */
    private static final String PINNED_EXPERT_LABEL = "指定专家";

    private final AiSessionMapper sessionMapper;

    private final IAiAgentService agentService;

    private final IAiModelConfigService modelConfigService;

    /**
     * 校验本会话接收图片消息的对话模型是否声明「支持视觉」，不支持时抛出业务异常。
     *
     * @param sessionId 会话主键
     */
    public void assertImageSupported(Long sessionId) {
        AiSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        List<String> offenders = new ArrayList<>();
        collectOffender(offenders, agentService.queryEntity(session.getAgentId()), null);
        collectOffender(offenders, agentService.queryEntity(session.getPinnedExpertId()), PINNED_EXPERT_LABEL);
        if (!offenders.isEmpty()) {
            throw new ServiceException("图片需要「支持视觉」的对话模型，但以下模型未声明该能力："
                + String.join("；", offenders)
                + "。请在「模型配置」中为其勾选「支持视觉」，或改用视觉模型（如 qwen-vl 系列）后再上传图片");
        }
    }

    /**
     * 收集一个「未声明支持视觉」的模型描述（模型缺失 / 未配置 / 已声明支持时不收集）。
     *
     * @param offenders 收集结果
     * @param agent     智能体定义（可为 null）
     * @param roleLabel 角色前缀（入口传 null）
     */
    private void collectOffender(List<String> offenders, AiAgent agent, String roleLabel) {
        if (agent == null || agent.getModelId() == null) {
            return;
        }
        AiModelConfigVo model = modelConfigService.queryById(agent.getModelId());
        if (model == null || !AiModelConfig.VISION_UNSUPPORTED.equals(model.getSupportsVision())) {
            return;
        }
        offenders.add((roleLabel == null ? "" : roleLabel)
            + "「" + agent.getAgentName() + "」的模型「" + model.getConfigName() + "」");
    }

}