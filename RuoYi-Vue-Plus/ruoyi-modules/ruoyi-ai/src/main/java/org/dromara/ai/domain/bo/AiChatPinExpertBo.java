package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 指定 / 取消指定专家请求体（REQ-AI-31）。
 *
 * <p>与其它请求体一致不做必填校验：{@code expertAgentId} 为 {@code null} 即「取消指定」，
 * 服务端据此把 {@code ai_session.pinned_expert_id} 置空并回到入口智能体的正常决策。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiChatPinExpertBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 专家智能体主键；{@code null} 表示取消指定
     */
    private Long expertAgentId;

}