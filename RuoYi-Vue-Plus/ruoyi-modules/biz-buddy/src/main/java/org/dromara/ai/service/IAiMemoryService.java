package org.dromara.ai.service;

import org.dromara.ai.core.memory.spi.MemoryBackendStats;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;

import java.util.List;

/**
 * AI 长期记忆 Service 接口（REQ-AI-09）。
 *
 * <p>记忆按用户隔离存放于智能体工作区，本接口提供记忆文件的查看、编辑与手动触发合并。</p>
 *
 * @author BizBuddy
 */
public interface IAiMemoryService {

    /**
     * 查询指定智能体与用户下的记忆文件清单（长期记忆 + 每日流水）。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @return 记忆文件清单
     */
    List<AiMemoryFileVo> listFiles(Long agentId, Long userId);

    /**
     * 读取记忆文件内容。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @param path    工作区相对路径
     * @return 文件内容
     */
    AiMemoryContentVo readContent(Long agentId, Long userId, String path);

    /**
     * 保存记忆文件内容（人工编辑）。
     *
     * @param bo 写入参数
     */
    void writeContent(AiMemoryContentBo bo);

    /**
     * 手动触发记忆合并（把每日流水合并进 {@code MEMORY.md}）。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    void consolidate(Long agentId, Long userId);

    /**
     * 归档超过保留期的每日流水（移动到记忆目录下的 {@code archive/} 子目录，保留证据、释放上下文）。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @return 归档的文件数
     */
    int archiveDaily(Long agentId, Long userId);

    /**
     * 清理超过保留期的记忆文件与会话日志（流水按每日保留天数，会话日志按会话保留天数）。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @return 删除的文件数
     */
    int cleanup(Long agentId, Long userId);

    /**
     * 删除某用户的全部长期记忆（{@code MEMORY.md} + {@code memory/}，并清除合并水位）。
     *
     * <p>用于回滚 / 合规删除：删除后该用户在小Z 与专家侧都不再带历史记忆。</p>
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    void deleteAll(Long agentId, Long userId);

    /**
     * 判断该用户是否存在「即将归档且晚于合并水位」的每日流水。
     *
     * <p>每日维护任务据此只对不活跃用户补一次合并，避免把未合并的最后一轮流水直接移入归档目录。</p>
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @return 需要先合并时返回 true
     */
    boolean hasUnconsolidatedExpiringLedger(Long agentId, Long userId);

    /**
     * 列出工作区中存在记忆数据（{@code memory/} 目录或 {@code MEMORY.md}）的用户主键。
     *
     * <p>用于覆盖「手工写入记忆但从未对话」的用户，避免维护任务只扫描会话表造成遗漏。</p>
     *
     * @param agentId 智能体主键
     * @return 用户主键列表（升序）
     */
    List<Long> listMemoryUserIds(Long agentId);

    /**
     * 查询某用户的记忆规模与维护状态。
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @return 记忆统计
     */
    MemoryBackendStats stats(Long agentId, Long userId);

}