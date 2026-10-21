package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 首登检查清单单项。
 *
 * <p>只返回「是否完成」与引导文案，不携带任何密钥 / 配置值；前端据 {@link #key} 决定跳转目标。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiOnboardingItemVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 稳定标识：chat-model / embedding-model / document / first-chat
     */
    private String key;

    /**
     * 清单标题
     */
    private String title;

    /**
     * 引导说明
     */
    private String description;

    /**
     * 是否已完成
     */
    private boolean done;

}
