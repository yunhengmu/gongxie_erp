package org.dromara.ai.core.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次执行的取消注册表（基于 AgentScope 2.0.4 的 {@code AgentRun} / {@code RunControl}）。
 *
 * <p>一轮对话里可能**同时**存在多段可取消执行：例如「指定专家兜底」的外层订阅与它内部那次
 * 专家调用，是两个互相独立、需要各自取消的句柄。故按会话登记为**集合**（而非单槽），
 * 先结束的一段注销时不会误删仍在跑的那段。</p>
 *
 * <p>对话页「停止」与 SSE 断连经 {@link #cancel(Long)} 取消该会话**当前全部**执行，
 * 避免出现「前端已停、后端仍在跑并继续落库」。</p>
 *
 * <p>句柄是运行期内存态，不落库、不跨副本（同一会话的流式执行本就绑定在单个实例上）。</p>
 *
 * @author BizBuddy
 */
@Component
public class AiRunRegistry {

    /**
     * 会话主键 → 该会话当前登记的可取消句柄集合
     */
    private final Map<Long, Set<Cancelable>> active = new ConcurrentHashMap<>();

    /**
     * 登记该会话的一段可取消执行。
     *
     * @param sessionId  会话主键
     * @param cancelable 取消句柄
     */
    void register(Long sessionId, Cancelable cancelable) {
        active.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(cancelable);
    }

    /**
     * 注销取消句柄（按**身份**移除，避免误删仍在跑的另一段执行）。
     *
     * @param sessionId  会话主键
     * @param cancelable 取消句柄
     */
    void unregister(Long sessionId, Cancelable cancelable) {
        active.computeIfPresent(sessionId, (key, handles) -> {
            handles.remove(cancelable);
            return handles.isEmpty() ? null : handles;
        });
    }

    /**
     * 判断该会话当前是否有进行中的执行。
     *
     * <p>句柄是单实例内存态，多副本部署时只能反映本实例；重新生成等「先检查再执行」的
     * 场景用它做同实例互斥，跨副本并发仍由会话内消息状态兜底。</p>
     *
     * @param sessionId 会话主键
     * @return 是否存在活跃执行
     */
    public boolean isActive(Long sessionId) {
        Set<Cancelable> handles = active.get(sessionId);
        return handles != null && !handles.isEmpty();
    }

    /**
     * 取消该会话当前登记的全部执行。
     *
     * @param sessionId 会话主键
     * @return 是否至少取消了一段仍在进行的执行
     */
    public boolean cancel(Long sessionId) {
        Set<Cancelable> handles = active.get(sessionId);
        if (handles == null || handles.isEmpty()) {
            return false;
        }
        boolean cancelled = false;
        for (Cancelable handle : handles) {
            cancelled |= handle.cancel();
        }
        return cancelled;
    }

    /**
     * 可取消句柄。
     */
    @FunctionalInterface
    interface Cancelable {

        /**
         * 取消当前执行。
         *
         * @return 是否取消了仍在进行的执行
         */
        boolean cancel();
    }
}