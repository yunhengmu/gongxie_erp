package org.dromara.ai.service;

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
     * <p>替代框架「&gt;90 天归档」在用户级隔离目录下不生效的问题（与自实现合并同源）。</p>
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
     * 由对话链路在每轮收尾调用的**自动合并**入口（REQ-AI-09）：判定通过才真正合并。
     *
     * <p>只负责提交（延迟异步执行），不阻塞本轮回答；是否执行由三件事共同决定：
     * ① 长期记忆开关已开启；② 距上次合并已超过「合并最小间隔」；③ 期间有新增的每日流水。
     * 任一不满足即跳过，避免无收益的额外模型调用。</p>
     *
     * <p><b>为什么需要它</b>：框架的自动合并（{@code MemoryConsolidator}）在「按用户隔离」的
     * 目录结构下读不到流水、恒跳过，导致 {@code MEMORY.md} 永不生成，新对话看不到已记事实；
     * 故由平台自实现的合并 + 本入口补齐自动链路。</p>
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    void consolidateIfDue(Long agentId, Long userId);

}