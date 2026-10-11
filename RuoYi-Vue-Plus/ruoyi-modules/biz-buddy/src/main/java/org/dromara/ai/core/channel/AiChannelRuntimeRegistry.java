package org.dromara.ai.core.channel;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.service.IAiChannelService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道运行期装配的缓存与生命周期管理（REQ-AI-24）。
 *
 * <p>按渠道编码缓存 {@link AiChannelRuntime}，指纹为「渠道主键 + 更新时间」：
 * 渠道配置（凭据 / 默认智能体 / 启停）变更后指纹变化，下一次装配即**自动重建**，
 * 无需重启应用。</p>
 *
 * <p><b>长连接平台（钉钉）与 Webhook 平台的区别</b>：Webhook 平台由外部平台回调驱动，
 * 装配在被回调时按需建立即可；钉钉需**应用主动建连**，故本类额外承担其生命周期：
 * ① 应用就绪后按启用中的钉钉渠道拉起连接（{@link #startLongConnectionChannels()}）；
 * ② 管理面保存 / 删除渠道后由控制层调用 {@link #refresh(String)} 重建或断连；
 * ③ 应用关闭时 {@link #shutdown()} 全部停机。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChannelRuntimeRegistry {

    private final IAiChannelService channelService;

    private final AiChannelInboundService inboundService;

    private final AiChannelStreamStatus streamStatus;

    /**
     * 入站事件幂等（Redis，多副本共享）
     */
    private final RedisInboundEventDeduplicator channelDeduplicator;

    /**
     * 渠道级入站限流（Redis，多副本共享）
     */
    private final AiChannelRateLimiter channelRateLimiter;

    /**
     * 运行期装配缓存：渠道编码 → 装配
     */
    private final Map<String, AiChannelRuntime> cache = new ConcurrentHashMap<>();

    /**
     * 应用就绪后拉起启用中的长连接渠道（钉钉）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startLongConnectionChannels() {
        List<String> codes = channelService.listEnabledChannelCodes(AiChannel.PLATFORM_DINGTALK);
        for (String code : codes) {
            resolve(code);
        }
        if (!codes.isEmpty()) {
            log.info("已按启用中的长连接渠道拉起连接：count={}", codes.size());
        }
    }

    /**
     * 解析渠道的运行期装配（不存在 / 停用 / 装配失败时返回 null）。
     *
     * <p>指纹一致则复用缓存；指纹变化则重建（先停旧连接）。首次装配或重建后立即
     * {@link AiChannelRuntime#start()}（钉钉建连、Webhook 平台为 no-op）。</p>
     *
     * @param channelCode 渠道编码
     * @return 运行期装配，或 null
     */
    public AiChannelRuntime resolve(String channelCode) {
        AiChannel channel = channelService.loadRuntimeConfig(channelCode);
        if (channel == null) {
            evict(channelCode);
            return null;
        }
        AiChannelRuntime cached = cache.get(channelCode);
        String fingerprint = channel.getId() + "|" + channel.getUpdateTime();
        if (cached != null && fingerprint.equals(cached.getFingerprint())) {
            return cached;
        }
        if (cached != null) {
            stopQuietly(cached);
            cache.remove(channelCode);
        }
        try {
            AiChannelRuntime runtime = AiChannelRuntime.build(channel, message -> {
                AiChannelRuntime current = cache.get(channelCode);
                if (current == null) {
                    log.warn("渠道长连接收到消息但装配已失效，丢弃：channelCode={}", channelCode);
                    return;
                }
                inboundService.submit(current, message);
            }, channelDeduplicator, channelRateLimiter);
            cache.put(channelCode, runtime);
            log.info("渠道运行期装配已{}：channelCode={}, platform={}",
                cached == null ? "建立" : "重建", channelCode, channel.getPlatformType());
            startRuntime(runtime);
            return runtime;
        } catch (Exception e) {
            log.error("渠道运行期装配失败，channelCode={}", channelCode, e);
            cache.remove(channelCode);
            streamStatus.markFailed(channelCode, e.getMessage());
            return null;
        }
    }

    /**
     * 按最新配置重建渠道装配并重新拉起（管理面新增 / 修改 / 删除渠道后调用）。
     *
     * @param channelCode 渠道编码
     */
    public void refresh(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return;
        }
        evict(channelCode);
        resolve(channelCode);
    }

    /**
     * 停止并移除渠道的运行期装配（渠道停用 / 删除 / 配置变更时调用）。
     *
     * @param channelCode 渠道编码
     */
    public void evict(String channelCode) {
        AiChannelRuntime removed = cache.remove(channelCode);
        stopQuietly(removed);
        streamStatus.remove(channelCode);
    }

    /**
     * 启动装配的运行期资源，并登记长连接状态。
     *
     * @param runtime 运行期装配
     */
    private void startRuntime(AiChannelRuntime runtime) {
        try {
            runtime.start();
            if (AiChannel.PLATFORM_DINGTALK.equals(runtime.getPlatformType())) {
                streamStatus.markRunning(runtime.getChannelCode());
            }
        } catch (Exception e) {
            log.error("渠道运行期资源启动失败，channelCode={}", runtime.getChannelCode(), e);
            streamStatus.markFailed(runtime.getChannelCode(), e.getMessage());
        }
    }

    /**
     * 容错停机（停止失败只告警，不阻断装配切换与删除）。
     *
     * @param runtime 运行期装配（可为 null）
     */
    private void stopQuietly(AiChannelRuntime runtime) {
        if (runtime == null) {
            return;
        }
        try {
            runtime.stop();
        } catch (Exception e) {
            log.warn("渠道运行期资源停止失败：channelCode={}，原因={}", runtime.getChannelCode(), e.getMessage());
        }
    }

    /**
     * 应用关闭时停止全部长连接。
     */
    @PreDestroy
    public void shutdown() {
        List<String> codes = new ArrayList<>(cache.keySet());
        for (String code : codes) {
            evict(code);
        }
        log.info("渠道运行期装配已全部停止：count={}", codes.size());
    }

}