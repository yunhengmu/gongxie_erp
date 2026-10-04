package org.dromara.ai.core.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelRuntimeRegistry} 单元测试：缓存指纹、重建、生命周期与状态登记。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("渠道运行期装配缓存")
class AiChannelRuntimeRegistryTest {

    @Mock
    private IAiChannelService channelService;
    @Mock
    private AiChannelInboundService inboundService;

    private AiChannelStreamStatus streamStatus;
    private AiChannelRuntimeRegistry registry;
    private MockedStatic<AiChannelRuntime> runtimeStatic;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        streamStatus = new AiChannelStreamStatus();
        registry = new AiChannelRuntimeRegistry(channelService, inboundService, streamStatus);
        runtimeStatic = mockStatic(AiChannelRuntime.class);
    }

    @AfterEach
    void tearDown() {
        runtimeStatic.close();
    }

    @Test
    @DisplayName("渠道不存在时返回 null 且不装配")
    void shouldReturnNullWhenChannelMissing() {
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(null);

        assertNull(registry.resolve("ch-1"));
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), never());
    }

    @Test
    @DisplayName("指纹一致时复用缓存，仅装配一次")
    void shouldCacheByFingerprint() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        assertSame(runtime, registry.resolve("ch-1"));
        assertSame(runtime, registry.resolve("ch-1"));
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), times(1));
        verify(runtime).start();
    }

    @Test
    @DisplayName("指纹变化时先停旧再重建")
    void shouldRebuildWhenFingerprintChanges() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime first = runtimeMock("1|" + channel.getUpdateTime(), "github");
        AiChannelRuntime second = runtimeMock("1|" + LocalDateTime.of(2026, 2, 2, 0, 0), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(first, second);

        assertSame(first, registry.resolve("ch-1"));
        channel.setUpdateTime(LocalDateTime.of(2026, 2, 2, 0, 0));
        assertSame(second, registry.resolve("ch-1"));

        verify(first).stop();
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), times(2));
    }

    @Test
    @DisplayName("装配抛异常时返回 null 并登记失败")
    void shouldMarkFailedWhenBuildThrows() {
        when(channelService.loadRuntimeConfig("ch-1"))
            .thenReturn(channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0)));
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any()))
            .thenThrow(new RuntimeException("bad-config"));

        assertNull(registry.resolve("ch-1"));
        assertEquals(AiChannelStreamStatus.FAILED, streamStatus.get("ch-1").status());
    }

    @Test
    @DisplayName("钉钉渠道启动成功后登记运行中")
    void shouldMarkRunningForDingTalk() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        channel.setPlatformType(AiChannel.PLATFORM_DINGTALK);
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "dingtalk");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        registry.resolve("ch-1");

        assertEquals(AiChannelStreamStatus.RUNNING, streamStatus.get("ch-1").status());
    }

    @Test
    @DisplayName("启动失败时登记失败但仍返回装配")
    void shouldMarkFailedWhenStartThrows() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "dingtalk");
        doThrow(new RuntimeException("start-fail")).when(runtime).start();
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        assertNotNull(registry.resolve("ch-1"));
        assertEquals(AiChannelStreamStatus.FAILED, streamStatus.get("ch-1").status());
    }

    @Test
    @DisplayName("refresh 对空编码直接返回")
    void shouldIgnoreRefreshBlank() {
        registry.refresh(null);
        registry.refresh("  ");
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), never());
    }

    @Test
    @DisplayName("refresh 清除缓存后重新装配")
    void shouldRefreshByEvictAndResolve() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime first = runtimeMock("1|" + channel.getUpdateTime(), "github");
        AiChannelRuntime second = runtimeMock("1|" + channel.getUpdateTime(), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(first, second);

        registry.resolve("ch-1");
        registry.refresh("ch-1");

        verify(first).stop();
        verify(second).start();
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), times(2));
    }

    @Test
    @DisplayName("evict 停止装配并清除长连接状态")
    void shouldEvictRuntime() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "dingtalk");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);
        registry.resolve("ch-1");

        registry.evict("ch-1");

        verify(runtime).stop();
        assertNull(streamStatus.get("ch-1"));
    }

    @Test
    @DisplayName("停车失败被容错")
    void shouldTolerateStopFailure() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "github");
        doThrow(new RuntimeException("stop-fail")).when(runtime).stop();
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);
        registry.resolve("ch-1");

        registry.evict("ch-1");
    }

    @Test
    @DisplayName("应用就绪后拉起启用中的长连接渠道")
    void shouldStartLongConnectionChannels() {
        when(channelService.listEnabledChannelCodes(AiChannel.PLATFORM_DINGTALK))
            .thenReturn(List.of("ch-1"));
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        channel.setPlatformType(AiChannel.PLATFORM_DINGTALK);
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "dingtalk");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        registry.startLongConnectionChannels();

        verify(runtime).start();
        assertEquals(AiChannelStreamStatus.RUNNING, streamStatus.get("ch-1").status());
    }

    @Test
    @DisplayName("无启用渠道时启动钩子不装配")
    void shouldNotStartWhenNoChannels() {
        when(channelService.listEnabledChannelCodes(anyString())).thenReturn(List.of());

        registry.startLongConnectionChannels();

        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), any()), never());
    }

    @Test
    @DisplayName("关闭时停止全部装配")
    void shouldShutdownAll() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);
        registry.resolve("ch-1");

        registry.shutdown();

        verify(runtime).stop();
    }

    @Test
    @DisplayName("入站回调：装配仍在缓存时转交入站处理")
    @SuppressWarnings("unchecked")
    void shouldDispatchInboundWhenCached() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        registry.resolve("ch-1");
        Consumer<InboundMessage> handler = captureInboundHandler();
        InboundMessage message = dm();
        handler.accept(message);

        verify(inboundService).submit(runtime, message);
    }

    @Test
    @DisplayName("入站回调：装配已失效时丢弃")
    @SuppressWarnings("unchecked")
    void shouldDropInboundWhenEvicted() {
        AiChannel channel = channel(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(channelService.loadRuntimeConfig("ch-1")).thenReturn(channel);
        AiChannelRuntime runtime = runtimeMock("1|" + channel.getUpdateTime(), "github");
        runtimeStatic.when(() -> AiChannelRuntime.build(any(), any())).thenReturn(runtime);

        registry.resolve("ch-1");
        Consumer<InboundMessage> handler = captureInboundHandler();
        registry.evict("ch-1");
        handler.accept(dm());

        verify(inboundService, never()).submit(any(), any());
    }

    @SuppressWarnings("unchecked")
    private Consumer<InboundMessage> captureInboundHandler() {
        ArgumentCaptor<Consumer<InboundMessage>> captor = ArgumentCaptor.forClass(Consumer.class);
        runtimeStatic.verify(() -> AiChannelRuntime.build(any(), captor.capture()));
        return captor.getValue();
    }

    private InboundMessage dm() {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        return InboundMessage.builder("ch-1", Peer.direct("u1"), List.of(msg)).senderId("u1").build();
    }

    private AiChannelRuntime runtimeMock(String fingerprint, String platformType) {
        AiChannelRuntime runtime = mock(AiChannelRuntime.class);
        when(runtime.getFingerprint()).thenReturn(fingerprint);
        when(runtime.getPlatformType()).thenReturn(platformType);
        when(runtime.getChannelCode()).thenReturn("ch-1");
        return runtime;
    }

    private AiChannel channel(Long id, LocalDateTime updateTime) {
        AiChannel channel = new AiChannel();
        channel.setId(id);
        channel.setChannelCode("ch-1");
        channel.setPlatformType(AiChannel.PLATFORM_GITHUB);
        channel.setUpdateTime(updateTime);
        return channel;
    }

}