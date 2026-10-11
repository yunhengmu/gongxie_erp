package org.dromara.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.vo.AiSourceVo;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检索来源注册表：临时暂存「某次知识库检索命中的结构化来源」，供事件桥按 toolCallId 取用。
 *
 * <p>工具执行（Reactor 线程）与事件桥（订阅回调线程）不在同一调用栈，工具命中结果只把
 * 「渲染文本」交给模型，结构化来源无法随 {@code ToolResultBlock} 传出，故用本注册表按
 * toolCallId（即 AgentScope toolUseBlock.id）中转：工具写入、桥接 {@code drain} 取出即删。</p>
 *
 * <p>带时间戳 TTL 兜底：异常路径下工具已写入而桥接未取用时，由下一次 {@code put} 顺带清理过期项，
 * 避免长期泄漏。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiRetrievalSourceRegistry {

    /**
     * 默认存活时长（毫秒）：远超单轮工具调用时长即可，仅作异常路径兜底
     */
    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private final long ttlMillis;

    public AiRetrievalSourceRegistry() {
        this(DEFAULT_TTL_MILLIS);
    }

    /**
     * 指定 TTL 构造（供测试注入较短存活时长）。
     *
     * @param ttlMillis 存活时长（毫秒）
     */
    AiRetrievalSourceRegistry(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /**
     * 暂存某次工具调用命中的来源，并顺带清理过期项。
     *
     * @param toolCallId 工具调用标识
     * @param sources    结构化来源（空集合时忽略）
     */
    public void put(String toolCallId, List<AiSourceVo> sources) {
        if (StringUtils.isBlank(toolCallId) || sources == null || sources.isEmpty()) {
            return;
        }
        cleanupExpired();
        store.put(toolCallId, new Entry(List.copyOf(sources), System.currentTimeMillis()));
    }

    /**
     * 取出并删除某次工具调用的来源（取出即删）。
     *
     * @param toolCallId 工具调用标识
     * @return 结构化来源；无对应项时返回空集合
     */
    public List<AiSourceVo> drain(String toolCallId) {
        if (StringUtils.isBlank(toolCallId)) {
            return List.of();
        }
        Entry entry = store.remove(toolCallId);
        return entry == null ? List.of() : entry.sources();
    }

    /**
     * 清理已过期项。
     */
    private void cleanupExpired() {
        long deadline = System.currentTimeMillis() - ttlMillis;
        store.entrySet().removeIf(entry -> entry.getValue().createdAt() < deadline);
    }

    /**
     * 注册项（来源 + 写入时间戳）。
     *
     * @param sources   结构化来源
     * @param createdAt 写入时间（毫秒）
     */
    private record Entry(List<AiSourceVo> sources, long createdAt) {
    }
}
