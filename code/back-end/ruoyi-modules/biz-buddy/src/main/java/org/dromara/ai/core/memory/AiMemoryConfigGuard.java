package org.dromara.ai.core.memory;

import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.common.core.exception.ServiceException;

/**
 * 长期记忆配置的跨字段护栏（docs/24 §5.8）。
 *
 * <p>单字段注解只能约束取值范围，无法表达「归档保留期不得短于流水保留期」这类字段间关系；
 * 保存配置前统一在此校验，避免出现「归档即删除」或「合并预算挤占注入预算」的误配置。</p>
 *
 * @author BizBuddy
 */
public final class AiMemoryConfigGuard {

    /**
     * 合并产出预算占注入预算的上限（百分比）
     */
    private static final int CONSOLIDATION_BUDGET_PERCENT = 60;

    /**
     * Token 与字符的粗略折算比例（1 Token ≈ 4 字符），两侧预算同比例折算后比较
     */
    private static final int CHARS_PER_TOKEN = 4;

    private AiMemoryConfigGuard() {
    }

    /**
     * 校验配置字段间关系，不满足时抛出业务异常（中文原因可直接回显给运维）。
     *
     * @param policy 长期记忆配置
     */
    public static void validate(AiMemoryConfigBo policy) {
        if (policy == null) {
            return;
        }
        // 护栏零：管道模式必须是已知三档，避免写入手工拼写导致装配期静默走错分支
        String mode = policy.getPipelineMode();
        if (mode != null && !mode.isBlank()
            && !"framework".equals(mode) && !"shadow".equals(mode) && !"platform".equals(mode)) {
            throw new ServiceException("记忆管道模式只能是 framework / shadow / platform");
        }
        // 护栏一：归档保留期短于流水保留期时，刚归档的流水会在同一轮清理中被删除，等于没有归档
        if (policy.getArchiveRetentionDays() < policy.getDailyFileRetentionDays()) {
            throw new ServiceException("归档保留天数不能小于流水保留天数，否则归档后会立即被清理");
        }
        // 护栏二：合并产物最终写入 MEMORY.md 参与注入，产出预算过大会挤占系统提示词预算。
        // 两侧都按「Token × 4 = 字符」折算后比较，即要求 合并预算 × 4 ≤ 注入预算 × 60% × 4
        long consolidationChars = (long) policy.getConsolidationMaxTokens() * CHARS_PER_TOKEN;
        long injectionBudgetChars =
            (long) policy.getMaxContextTokens() * CONSOLIDATION_BUDGET_PERCENT / 100 * CHARS_PER_TOKEN;
        if (consolidationChars > injectionBudgetChars) {
            throw new ServiceException("合并 Token 上限过大：不能超过记忆注入预算的 "
                + CONSOLIDATION_BUDGET_PERCENT + "%");
        }
    }
}
