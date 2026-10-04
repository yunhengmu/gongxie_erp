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