package org.dromara.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;

import java.util.function.Consumer;

/**
 * 飞书「长连接」接收事件的薄封装（REQ-AI-24）。
 *
 * <p>飞书长连接的帧为私有 protobuf 协议、只有官方 SDK {@code com.larksuite.oapi:oapi-sdk} 能建立，
 * 故此处仅做一层薄封装：装配 {@link EventDispatcher}，在 {@code im.message.receive_v1} 上注册
 * 自定义处理器，把 SDK 交付的**原始事件报文（schema 2.0 信封、明文）**交给注入的 {@code onEvent}。
 * 与钉钉长连接不同，飞书长连接建立时即完成鉴权，报文不再加密、不再验签，故无需 Encrypt Key / Verification Token。</p>
 *
 * <p><b>3 秒约束</b>：飞书要求收到事件后 3 秒内返回 ACK，因此 {@code onEvent} 必须**只做入队、不阻塞**
 * （本项目入队后由 {@link AiChannelInboundService} 异步执行）。</p>
 *
 * <p>连接生命周期由调用方（{@link AiChannelRuntime}）通过 {@link #start()} / {@link #stop()} 管理；
 * 断线重连由 SDK 自身处理（{@code autoReconnect} 默认开启），此处仅转发重连事件用于日志。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class FeishuStreamClient {

    /**
     * 事件类型：接收消息（长连接订阅的事件）
     */
    private static final String EVENT_TYPE_MESSAGE_RECEIVE = "im.message.receive_v1";

    /**
     * 建连握手等待超时（毫秒）
     */
    private static final long READY_TIMEOUT_MS = 10_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 官方 SDK 长连接客户端
     */
    private final Client client;

    /**
     * 事件回调：原始事件报文（schema 2.0 信封）
     */
    private final Consumer<JsonNode> onEvent;

    /**
     * @param appId     应用标识
     * @param appSecret 应用密钥（明文）
     * @param domain    长连接 domain 基址（含 scheme，如 {@code https://open.feishu.cn}；留空走 SDK 默认）
     * @param onEvent   事件回调（只做入队、不阻塞）
     */
    public FeishuStreamClient(String appId, String appSecret, String domain, Consumer<JsonNode> onEvent) {
        this.onEvent = onEvent;
        // 飞书长连接建连即鉴权、报文明文，故 dispatcher 的 Verification Token / Encrypt Key 一律留空。
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
            .onCustomizedEvent(EVENT_TYPE_MESSAGE_RECEIVE, new CustomEventHandler() {
                @Override
                public void handle(EventReq event) {
                    dispatch(event);
                }
            })
            .build();
        Client.Builder builder = new Client.Builder(appId, appSecret).eventHandler(dispatcher);
        if (StringUtils.isNotBlank(domain)) {
            // SDK 内部按 domain + 固定路径拼接，故去掉可能存在的尾斜杠
            builder.domain(domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain.trim());
        }
        builder.onReconnecting(() -> log.info("飞书长连接重连中：appId={}", appId));
        builder.onReconnected(() -> log.info("飞书长连接已重连：appId={}", appId));
        this.client = builder.build();
    }

    /**
     * 发起建连并等待握手完成。
     *
     * @throws Exception 建连失败（SDK 抛 {@code ServerUnreachableException} 等）
     */
    public void start() throws Exception {
        client.start();
        client.awaitReady(READY_TIMEOUT_MS);
    }

    /**
     * 主动断开连接（{@code close()} 后 SDK 不再自动重连）。
     */
    public void stop() {
        client.close();
    }

    /**
     * 把 SDK 交付的原始事件报文解析为 JSON 并转交回调；解析失败只告警，不阻断 SDK 的 ACK。
     *
     * @param event 事件请求（{@code getBody()} 为 schema 2.0 信封的原始 JSON）
     */
    private void dispatch(EventReq event) {
        try {
            JsonNode payload = OBJECT_MAPPER.readTree(event.getBody());
            onEvent.accept(payload);
        } catch (Exception e) {
            log.warn("飞书长连接事件报文解析失败，已丢弃：原因={}", e.getMessage());
        }
    }

}
