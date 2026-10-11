package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编排预算配置业务对象（REQ-AI-30 Phase 2 第四项）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.chat.route-budget-expert-tokens} /
 * {@code bizbuddy.ai.chat.route-budget-seconds}，页面保存后写入 Redis 覆盖默认值，
 * 下一次编排即按新预算生效。两项均为 0 表示不限。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiRouteBudgetConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 单轮编排的专家轮 Token 预算（专家调用输入 + 输出累计），0 表示不限
     */
    @Min(value = 0, message = "专家轮 Token 预算不能为负数（0 表示不限）")
    private long expertTokens;

    /**
     * 单轮编排的墙钟预算（秒），0 表示不限
     */
    @Min(value = 0, message = "编排墙钟预算不能为负数（0 表示不限）")
    private int wallSeconds;

}