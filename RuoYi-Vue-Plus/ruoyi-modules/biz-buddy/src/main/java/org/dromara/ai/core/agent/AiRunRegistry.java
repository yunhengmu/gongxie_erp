package org.dromara.ai.core.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次执行的取消注册表（基于 AgentScope 2.0.4 的 {@code AgentRun} / {@code RunControl}）。
 *
 * <p>一个会话同一时刻只服务一段流式执行：该段开始时登记取消句柄，暂停 / 完成 / 失败 / 取消时注销。
 * 对话页「停止」与 SSE 断连都经此取消**后端**执行，避免出现「前端已停、后端仍在跑并继续落库」。</p>
 *
 * <p>句柄是运行期内存态，不落库、不跨副本（同一会话的流式执行本就绑定在单个实例上）。</p>
 *
 * @author BizBuddy
 */
@Component
public class AiRunRegistry {

    /**
     * 会话主键 → 当前可取消句柄（同一会话同一时刻至多一段执行）
     */
    private final Map<Long, Cancelable> active = new ConcurrentHashMap<>();

    /**
     * 登记该会话当前执行的取消句柄（覆盖同一会话的旧句柄）。
     *
     * @param sessionId  会话主键
     * @param cancelable 取消句柄
     */
    void register(Long sessionId, Cancelable cancelable) {
        active.put(sessionId, cancelable);
    }

    /**
     * 注销取消句柄（仅当仍是该句柄时移除，避免误删下一段的登记）。
     *
     * @param sessionId  会话主键
     * @param cancelable 取消句柄
     */
    void unregister(Long sessionId, Cancelable cancelable) {
        active.remove(sessionId, cancelable);
    }

    /**
     * 取消该会话当前执行。
     *
     * @param sessionId 会话主键
     * @return 是否确实取消了一段仍在进行的执行
     */
    public boolean cancel(Long sessionId) {
        Cancelable cancelable = active.get(sessionId);
        return cancelable != null && cancelable.cancel();
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