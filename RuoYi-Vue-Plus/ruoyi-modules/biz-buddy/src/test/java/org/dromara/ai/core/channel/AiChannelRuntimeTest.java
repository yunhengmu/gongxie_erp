package org.dromara.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.dingtalk.DingTalkStreamClient;
import io.agentscope.extensions.channel.github.GitHubOutboundClient;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelRuntime} 单元测试：四种平台装配、长连接生命周期与出站分发。
 */
@DisplayName("渠道运行期装配")
class AiChannelRuntimeTest {

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    private AiChannel channel(String platform) {
        AiChannel channel = new AiChannel();
        channel.setId(1L);
        channel.setChannelCode("ch-1");
        channel.setPlatformType(platform);
        channel.setAgentId(9L);
        channel.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return channel;
    }

    @Test
    @DisplayName("飞书装配：基础字段与构件，未配置加密密钥时无 FeishuCrypto")
    void shouldBuildFeishuRuntime() {
        AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
        channel.setAppId("cli_x");
        channel.setAppSecret("secret");
        channel.setVerificationToken("vtoken");
        Consumer<io.agentscope.harness.agent.gateway.channel.InboundMessage> handler = msg -> {
        };

        AiChannelRuntime runtime = AiChannelRuntime.build(channel, handler);

        assertEquals(1L, runtime.getChannelId().longValue());
        assertEquals("ch-1", runtime.getChannelCode());
        assertEquals(AiChannel.PLATFORM_FEISHU, runtime.getPlatformType());
        assertEquals(9L, runtime.getAgentId().longValue());
        assertEquals("1|" + channel.getUpdateTime(), runtime.getFingerprint());
        assertEquals("vtoken", runtime.getVerificationToken());
        assertEquals(handler, runtime.getInboundHandler());
        assertNotNull(runtime.getFeishuMapper());
        assertNotNull(runtime.getFeishuOutbound());
        assertNull(runtime.getFeishuCrypto());
        assertNull(runtime.getWeComOutbound());
        assertNull(runtime.getGitHubOutbound());
        assertNull(runtime.getDingTalkStream());
        // mode 留空（非 ws）时不是长连接
        assertNull(runtime.getFeishuStream());
        assertFalse(runtime.isLongConnection());
    }

    @Test
    @DisplayName("飞书长连接装配：mode=ws 时长连接构件就绪、不建 FeishuCrypto、出站照常")
    void shouldBuildFeishuLongConnectionRuntime() {
        try (MockedConstruction<FeishuStreamClient> construction = mockConstruction(FeishuStreamClient.class)) {
            AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
            channel.setAppId("cli_x");
            channel.setAppSecret("secret");
            channel.setMode(AiChannel.MODE_WS);
            channel.setEncryptKey("encrypt-key");

            AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
            });

            assertNotNull(runtime.getFeishuStream());
            assertTrue(runtime.isLongConnection());
            assertNull(runtime.getFeishuCrypto());
            assertNotNull(runtime.getFeishuMapper());
            assertNotNull(runtime.getFeishuOutbound());
            assertEquals(1, construction.constructed().size());
        }
    }

    @Test
    @DisplayName("飞书 Webhook 装配：mode=webhook 时无长连接客户端")
    void shouldBuildFeishuWebhookRuntimeWithoutStream() {
        AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
        channel.setAppId("cli_x");
        channel.setAppSecret("secret");
        channel.setMode(AiChannel.MODE_WEBHOOK);

        AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
        });

        assertNull(runtime.getFeishuStream());
        assertFalse(runtime.isLongConnection());
        assertNotNull(runtime.getFeishuMapper());
    }

    @Test
    @DisplayName("飞书长连接 start/stop 透传到长连接客户端")
    void shouldStartAndStopFeishuStream() throws Exception {
        try (MockedConstruction<FeishuStreamClient> construction = mockConstruction(FeishuStreamClient.class)) {
            AiChannelRuntime runtime = AiChannelRuntime.build(feishuWsChannel(), msg -> {
            });
            FeishuStreamClient stream = construction.constructed().get(0);

            runtime.start();
            runtime.stop();

            verify(stream).start();
            verify(stream).stop();
        }
    }

    @Test
    @DisplayName("飞书配置加密密钥时创建 FeishuCrypto")
    void shouldCreateFeishuCryptoWhenEncryptKeyPresent() {
        AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
        channel.setAppId("cli_x");
        channel.setAppSecret("secret");
        channel.setEncryptKey("encrypt-key");

        AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
        });

        assertNotNull(runtime.getFeishuCrypto());
    }

    @Test
    @DisplayName("企业微信装配：加解密与出站构件就绪")
    void shouldBuildWeComRuntime() {
        AiChannel channel = channel(AiChannel.PLATFORM_WECOM);
        channel.setAppId("corp");
        channel.setAppSecret("secret");
        channel.setVerificationToken("vtoken");
        channel.setEncryptKey("1234567890123456789012345678901234567890123");
        channel.setPlatformAgentId("1000002");

        AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
        });

        assertNotNull(runtime.getWeComCrypto());
        assertNotNull(runtime.getWeComMapper());
        assertNotNull(runtime.getWeComOutbound());
        assertNull(runtime.getFeishuOutbound());
        assertNull(runtime.getGitHubOutbound());
    }

    @Test
    @DisplayName("GitHub 装配：机器人登录名去空格")
    void shouldBuildGitHubRuntime() {
        AiChannel channel = channel(AiChannel.PLATFORM_GITHUB);
        channel.setWebhookSecret("whsec");
        channel.setApiToken("token");
        channel.setPlatformAgentId("  bizbot ");

        AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
        });

        assertNotNull(runtime.getGitHubVerifier());
        assertNotNull(runtime.getGitHubMapper());
        assertNotNull(runtime.getGitHubOutbound());
        assertEquals("bizbot", runtime.getGitHubBotLogin());
        assertNull(runtime.getWeComOutbound());
    }

    @Test
    @DisplayName("钉钉装配：长连接构件就绪且非钉钉构件为空")
    void shouldBuildDingTalkRuntime() {
        try (MockedConstruction<DingTalkStreamClient> construction =
                 mockConstruction(DingTalkStreamClient.class)) {
            AiChannelRuntime runtime = AiChannelRuntime.build(dingTalkChannel(), msg -> {
            });

            assertNotNull(runtime.getDingTalkMapper());
            assertNotNull(runtime.getDingTalkOutbound());
            assertNotNull(runtime.getDingTalkStream());
            assertNull(runtime.getFeishuOutbound());
            assertNull(runtime.getWeComOutbound());
            assertNull(runtime.getGitHubOutbound());
            assertEquals(1, construction.constructed().size());
        }
    }

    @Test
    @DisplayName("不支持的平台类型抛出 IllegalArgumentException")
    void shouldRejectUnsupportedPlatform() {
        AiChannel channel = channel("slack");
        assertThrows(IllegalArgumentException.class, () -> AiChannelRuntime.build(channel, msg -> {
        }));
    }

    @Test
    @DisplayName("非钉钉平台 start/stop 为 no-op")
    void shouldNoopStartStopForWebhookPlatform() {
        AiChannelRuntime runtime = AiChannelRuntime.build(gitHubChannel(), msg -> {
        });
        runtime.start();
        runtime.stop();
    }

    @Test
    @DisplayName("钉钉 start/stop 透传到长连接客户端")
    void shouldStartAndStopDingTalkStream() {
        try (MockedConstruction<DingTalkStreamClient> construction =
                 mockConstruction(DingTalkStreamClient.class)) {
            AiChannelRuntime runtime = AiChannelRuntime.build(dingTalkChannel(), msg -> {
            });
            DingTalkStreamClient stream = construction.constructed().get(0);

            runtime.start();
            runtime.stop();

            verify(stream).start();
            verify(stream).stop();
        }
    }

    @Test
    @DisplayName("出站：空消息直接返回空信号")
    void shouldReturnEmptyForEmptyOutbound() {
        AiChannelRuntime runtime = AiChannelRuntime.build(gitHubChannel(), msg -> {
        });
        assertNull(runtime.send(address(), List.of()).block());
    }

    @Test
    @DisplayName("出站：非空消息委派给对应平台客户端")
    void shouldDelegateOutbound() {
        try (MockedConstruction<GitHubOutboundClient> construction = mockConstruction(GitHubOutboundClient.class,
            (mock, context) -> when(mock.send(any(), anyList())).thenReturn(reactor.core.publisher.Mono.empty()))) {
            AiChannelRuntime runtime = AiChannelRuntime.build(gitHubChannel(), msg -> {
            });
            GitHubOutboundClient outbound = construction.constructed().get(0);

            Msg message = Msg.builder().role(MsgRole.ASSISTANT).textContent("hi").build();
            assertNull(runtime.send(address(), List.of(message)).block());
            verify(outbound).send(any(), anyList());
        }
    }

    @Test
    @DisplayName("钉钉入站：有效报文转交上层，重复 msgId 去重，非文本丢弃")
    @SuppressWarnings("unchecked")
    void shouldHandleDingTalkPayload() throws Exception {
        List<Object[]> captured = new java.util.ArrayList<>();
        try (MockedConstruction<DingTalkStreamClient> construction =
                 mockConstruction(DingTalkStreamClient.class,
                     (mock, context) -> captured.add(context.arguments().toArray()))) {
            AtomicInteger handled = new AtomicInteger();
            AiChannelRuntime runtime = AiChannelRuntime.build(dingTalkChannel(), msg -> handled.incrementAndGet(),
                new io.agentscope.extensions.channel.common.IdempotencyStore(), allowAllLimiter());
            Consumer<JsonNode> consumer = (Consumer<JsonNode>) captured.get(0)[1];
            ObjectMapper mapper = new ObjectMapper();
            JsonNode textPayload = mapper.readTree("{\"msgtype\":\"text\",\"msgId\":\"m1\","
                + "\"text\":{\"content\":\"你好\"},\"senderStaffId\":\"u1\"}");

            consumer.accept(textPayload);
            assertEquals(1, handled.get());

            consumer.accept(textPayload);
            assertEquals(1, handled.get());

            consumer.accept(mapper.readTree("{\"msgtype\":\"image\",\"msgId\":\"m2\"}"));
            assertEquals(1, handled.get());

            consumer.accept(mapper.readTree("{\"msgtype\":\"text\",\"text\":{\"content\":\"无发送者\"}}"));
            assertEquals(1, handled.get());
        }
    }

    @Test
    @DisplayName("钉钉入站：未配置处理入口时不抛异常")
    @SuppressWarnings("unchecked")
    void shouldIgnoreDingTalkPayloadWhenHandlerMissing() throws Exception {
        List<Object[]> captured = new java.util.ArrayList<>();
        try (MockedConstruction<DingTalkStreamClient> construction =
                 mockConstruction(DingTalkStreamClient.class,
                     (mock, context) -> captured.add(context.arguments().toArray()))) {
            AiChannelRuntime runtime = AiChannelRuntime.build(dingTalkChannel(), null,
                new io.agentscope.extensions.channel.common.IdempotencyStore(), allowAllLimiter());
            assertNull(runtime.getInboundHandler());
            Consumer<JsonNode> consumer = (Consumer<JsonNode>) captured.get(0)[1];
            JsonNode payload = new ObjectMapper().readTree("{\"msgtype\":\"text\",\"msgId\":\"m1\","
                + "\"text\":{\"content\":\"你好\"},\"senderStaffId\":\"u1\"}");
            consumer.accept(payload);
        }
    }

    @Test
    @DisplayName("钉钉入站：触发防自环限流后不再转交")
    @SuppressWarnings("unchecked")
    void shouldThrottleDingTalkPayload() throws Exception {
        List<Object[]> captured = new java.util.ArrayList<>();
        try (MockedConstruction<DingTalkStreamClient> construction =
                 mockConstruction(DingTalkStreamClient.class,
                     (mock, context) -> captured.add(context.arguments().toArray()))) {
            AtomicInteger handled = new AtomicInteger();
            // 限流：窗口内前 20 次放行、之后拒绝（与原 BotLoopGuard 默认阈值同口径），故第 21 次不再转交
            AtomicInteger calls = new AtomicInteger();
            AiChannelRateLimiter limiter = org.mockito.Mockito.mock(AiChannelRateLimiter.class);
            org.mockito.Mockito.when(limiter.allow(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> calls.incrementAndGet() <= 20);
            AiChannelRuntime runtime = AiChannelRuntime.build(dingTalkChannel(), msg -> handled.incrementAndGet(),
                new io.agentscope.extensions.channel.common.IdempotencyStore(), limiter);
            Consumer<JsonNode> consumer = (Consumer<JsonNode>) captured.get(0)[1];
            ObjectMapper mapper = new ObjectMapper();

            for (int i = 0; i < 21; i++) {
                consumer.accept(mapper.readTree("{\"msgtype\":\"text\",\"msgId\":\"m" + i + "\","
                    + "\"text\":{\"content\":\"hi\"},\"senderStaffId\":\"u1\"}"));
            }
            assertEquals(20, handled.get());
        }
    }

    @Test
    @DisplayName("飞书长连接入站：有效报文转交上层，重复 event_id 去重，结构异常丢弃")
    @SuppressWarnings("unchecked")
    void shouldHandleFeishuPayload() throws Exception {
        List<Object[]> captured = new java.util.ArrayList<>();
        try (MockedConstruction<FeishuStreamClient> construction =
                 mockConstruction(FeishuStreamClient.class,
                     (mock, context) -> captured.add(context.arguments().toArray()))) {
            AtomicInteger handled = new AtomicInteger();
            AiChannelRuntime runtime = AiChannelRuntime.build(feishuWsChannel(), msg -> handled.incrementAndGet(),
                new io.agentscope.extensions.channel.common.IdempotencyStore(), allowAllLimiter());
            // 构造参数顺序：appId / appSecret / domain / onEvent
            Consumer<JsonNode> consumer = (Consumer<JsonNode>) captured.get(0)[3];
            ObjectMapper mapper = new ObjectMapper();
            JsonNode textPayload = mapper.readTree(FEISHU_TEXT_EVENT);

            consumer.accept(textPayload);
            assertEquals(1, handled.get());

            // 重复 event_id 幂等丢弃
            consumer.accept(textPayload);
            assertEquals(1, handled.get());

            // 结构异常（无 event 节点）静默丢弃
            consumer.accept(mapper.readTree("{\"header\":{\"event_id\":\"ev-malformed\"}}"));
            assertEquals(1, handled.get());
        }
    }

    @Test
    @DisplayName("飞书长连接入站：触发防自环限流后不再转交")
    @SuppressWarnings("unchecked")
    void shouldThrottleFeishuPayload() throws Exception {
        List<Object[]> captured = new java.util.ArrayList<>();
        try (MockedConstruction<FeishuStreamClient> construction =
                 mockConstruction(FeishuStreamClient.class,
                     (mock, context) -> captured.add(context.arguments().toArray()))) {
            AtomicInteger handled = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            AiChannelRateLimiter limiter = org.mockito.Mockito.mock(AiChannelRateLimiter.class);
            org.mockito.Mockito.when(limiter.allow(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> calls.incrementAndGet() <= 20);
            AiChannelRuntime runtime = AiChannelRuntime.build(feishuWsChannel(), msg -> handled.incrementAndGet(),
                new io.agentscope.extensions.channel.common.IdempotencyStore(), limiter);
            Consumer<JsonNode> consumer = (Consumer<JsonNode>) captured.get(0)[3];
            ObjectMapper mapper = new ObjectMapper();

            for (int i = 0; i < 21; i++) {
                consumer.accept(mapper.readTree(feishuTextEvent("ev-" + i)));
            }
            assertEquals(20, handled.get());
        }
    }

    /**
     * 一条 schema 2.0 信封的飞书文本消息事件（长连接报文与 Webhook 报文体同构）。
     */
    private static final String FEISHU_TEXT_EVENT = feishuTextEvent("ev1");

    private static String feishuTextEvent(String eventId) {
        return "{\"schema\":\"2.0\","
            + "\"header\":{\"event_id\":\"" + eventId
            + "\",\"event_type\":\"im.message.receive_v1\",\"tenant_key\":\"tk\"},"
            + "\"event\":{"
            + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_1\"},\"sender_type\":\"user\"},"
            + "\"message\":{\"message_id\":\"om_" + eventId + "\",\"chat_id\":\"oc_1\",\"chat_type\":\"p2p\","
            + "\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"你好\\\"}\"}}}";
    }

    /** 永不限流的限流器（放行一切），用于「只验证幂等 / 非文本丢弃」的用例。 */
    private static AiChannelRateLimiter allowAllLimiter() {
        AiChannelRateLimiter limiter = org.mockito.Mockito.mock(AiChannelRateLimiter.class);
        org.mockito.Mockito.when(limiter.allow(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return limiter;
    }

    private AiChannel gitHubChannel() {
        AiChannel channel = channel(AiChannel.PLATFORM_GITHUB);
        channel.setWebhookSecret("whsec");
        channel.setApiToken("token");
        return channel;
    }

    private AiChannel dingTalkChannel() {
        AiChannel channel = channel(AiChannel.PLATFORM_DINGTALK);
        channel.setAppId("appkey");
        channel.setAppSecret("appsecret");
        channel.setPlatformAgentId("robotcode");
        return channel;
    }

    private AiChannel feishuWsChannel() {
        AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
        channel.setAppId("cli_x");
        channel.setAppSecret("secret");
        channel.setMode(AiChannel.MODE_WS);
        return channel;
    }

    private OutboundAddress address() {
        return new OutboundAddress("ch-1", null, "ch-1:direct:u1", null);
    }

    @Test
    @DisplayName("指纹由渠道主键与更新时间拼接（agentId 为空也能装配）")
    void shouldComputeFingerprint() {
        AiChannel channel = channel(AiChannel.PLATFORM_FEISHU);
        channel.setAppId("cli_x");
        channel.setAppSecret("secret");
        channel.setAgentId(null);
        AiChannelRuntime runtime = AiChannelRuntime.build(channel, msg -> {
        });
        assertNull(runtime.getAgentId());
        assertEquals("1|" + channel.getUpdateTime(), runtime.getFingerprint());
    }

}