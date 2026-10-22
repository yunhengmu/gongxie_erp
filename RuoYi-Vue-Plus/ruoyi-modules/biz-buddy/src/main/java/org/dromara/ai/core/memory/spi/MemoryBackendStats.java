package org.dromara.ai.core.memory.spi;

/**
 * 记忆规模与维护状态统计（docs/24 §5.7）。
 *
 * <p>字段口径与文件式记忆一致：Token 按「字符数 / 4」估算；时间字段为 ISO-8601 字符串，
 * 无记录时为空串，便于前端直接展示。</p>
 *
 * @param userId              用户主键
 * @param memoryChars         长期记忆字符数
 * @param estimatedTokens     长期记忆估算 Token 数
 * @param dailyFileCount      每日流水文件数
 * @param archiveFileCount    归档文件数
 * @param lastConsolidatedAt  最后合并水位（ISO 时间）
 * @param lastArchivedAt      最后归档时间（ISO 时间）
 * @param failedEventCount    审计日志中的失败事件数
 * @param lastBatches         最近一次成功合并执行的批次数
 * @param lastMergedFiles     最近一次成功合并纳入的流水文件数
 * @param lastRemainingFiles  最近一次成功合并后仍未处理的流水文件数（达到单轮批次上限时大于 0）
 * @author BizBuddy
 */
public record MemoryBackendStats(long userId, int memoryChars, int estimatedTokens, int dailyFileCount,
                                 int archiveFileCount, String lastConsolidatedAt, String lastArchivedAt,
                                 int failedEventCount, int lastBatches, int lastMergedFiles,
                                 int lastRemainingFiles) {

    /**
     * 空统计（后端不可用时降级返回）。
     *
     * @param userId 用户主键
     * @return 空统计
     */
    public static MemoryBackendStats empty(long userId) {
        return new MemoryBackendStats(userId, 0, 0, 0, 0, "", "", 0, 0, 0, 0);
    }
}
