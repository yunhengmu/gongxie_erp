package org.dromara.ai.core.memory.spi;

/**
 * 平台长期记忆后端契约（docs/24 §5.10）。
 *
 * <p>当前唯一实现是文件式记忆（{@code MEMORY.md} + 每日流水）；框架后续提供 mem0 / ReMe
 * 适配时，只需新增一个实现，净化、审计、计量、降级等治理逻辑可复用装饰器，不需要重写。</p>
 *
 * <p>实现约定：所有方法都可能被并发调用，实现方需保证线程安全；写入内容必须是已净化的文本。</p>
 *
 * @author BizBuddy
 */
public interface AiMemoryBackend {

    /**
     * 记录一条跨会话事实。
     *
     * @param command 写入命令
     */
    void record(MemoryRecordCommand command);

    /**
     * 读取可用于注入的记忆文本。
     *
     * @param query 查询条件
     * @return 记忆文本；无记忆时返回空串
     */
    String retrieve(MemoryQuery query);

    /**
     * 删除某用户的全部长期记忆（合规删除）。
     *
     * @param userId 用户主键
     */
    void delete(long userId);

    /**
     * 查询某用户的记忆规模与维护状态。
     *
     * @param userId 用户主键
     * @return 记忆统计
     */
    MemoryBackendStats stats(long userId);
}
