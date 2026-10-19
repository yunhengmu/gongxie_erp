package org.dromara.ai.core.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道长连接（钉钉 / 飞书 {@code mode=ws}）的运行状态登记（REQ-AI-24 扩展）。
 *
 * <p>Webhook 类平台有回调地址，配置是否正确可由平台侧回调自证；长连接渠道没有回调地址，
 * 故由装配侧把「是否已拉起 / 启动失败原因」登记在此，供渠道列表回显，
 * 让运维至少能看到连接是否被拉起。</p>
 *
 * <p>状态为**单实例内存快照**（与框架 `BotLoopGuard` / `IdempotencyStore` 同口径），
 * 不落库、不跨副本；只反映「本实例是否拉起长连接」，不代表与平台网关的实时连通性
 * （断线由各长连接客户端（钉钉 / 飞书 SDK）自动重连）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiChannelStreamStatus {

    /**
     * 状态：未启动
     */
    public static final String STOPPED = "stopped";

    /**
     * 状态：运行中（长连接已启动，断线自动重连）
     */
    public static final String RUNNING = "running";

    /**
     * 状态：启动失败
     */
    public static final String FAILED = "failed";

    /**
     * 状态快照：渠道编码 → 快照
     */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * 登记「运行中」。
     *
     * @param channelCode 渠道编码
     */
    public void markRunning(String channelCode) {
        snapshots.put(channelCode, new Snapshot(RUNNING, null, LocalDateTime.now()));
    }

    /**
     * 登记「已停止」。
     *
     * @param channelCode 渠道编码
     */
    public void markStopped(String channelCode) {
        snapshots.put(channelCode, new Snapshot(STOPPED, null, LocalDateTime.now()));
    }

    /**
     * 登记「启动失败」。
     *
     * @param channelCode 渠道编码
     * @param detail      失败原因（截断保存，供页面提示）
     */
    public void markFailed(String channelCode, String detail) {
        String trimmed = detail == null ? null : detail.substring(0, Math.min(detail.length(), 200));
        snapshots.put(channelCode, new Snapshot(FAILED, trimmed, LocalDateTime.now()));
        log.warn("渠道长连接启动失败：channelCode={}，原因={}", channelCode, trimmed);
    }

    /**
     * 取状态快照。
     *
     * @param channelCode 渠道编码
     * @return 快照；未登记过时返回 null
     */
    public Snapshot get(String channelCode) {
        return snapshots.get(channelCode);
    }

    /**
     * 清除状态（渠道被删除时）。
     *
     * @param channelCode 渠道编码
     */
    public void remove(String channelCode) {
        snapshots.remove(channelCode);
    }

    /**
     * 状态快照。
     *
     * @param status     状态（stopped / running / failed）
     * @param detail     失败原因（仅 failed 时有值）
     * @param updateTime 更新时间
     */
    public record Snapshot(String status, String detail, LocalDateTime updateTime) {
    }

}