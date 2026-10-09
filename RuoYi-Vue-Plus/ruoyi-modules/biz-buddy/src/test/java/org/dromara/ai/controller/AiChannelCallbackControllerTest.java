package org.dromara.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.feishu.FeishuCrypto;
import io.agentscope.extensions.channel.feishu.FeishuInboundMapper;
import io.agentscope.extensions.channel.github.GitHubInboundMapper;
import io.agentscope.extensions.channel.github.GitHubSignatureVerifier;
import io.agentscope.extensions.channel.wecom.WeComCrypto;
import io.agentscope.extensions.channel.wecom.WeComInboundMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import org.dromara.ai.core.channel.AiChannelInboundService;
import org.dromara.ai.core.channel.AiChannelRuntime;
import org.dromara.ai.core.channel.AiChannelRuntimeRegistry;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelCallbackController} 单元测试：三平台入站回调的验签、解密、幂等、防自环与归一化分支。
 */
@DisplayName("渠道入站回调 Controller")
class AiChannelCallbackControllerTest {

    private static final String CODE = "ch";

    private final AiChannelRuntimeRegistry registry = mock(AiChannelRuntimeRegistry.class);

    private final AiChannelInboundService inboundService = mock(AiChannelInboundService.class);

    private final AiChannelRuntime runtime = mock(AiChannelRuntime.class);

    private final AiChannelCallbackController controller =
        new AiChannelCallbackController(registry, inboundService);

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    private static InboundMessage dm() {
        return InboundMessage.dm(CODE, "p1", List.of());
    }

    // ---------------------------- 飞书 ----------------------------

    @Test
    @DisplayName("飞书：渠道未命中返回 404")
    void shouldFeishuNotFound() {
        ResponseEntity<String> r = controller.feishuCallback(CODE, null, null, null, "{}");
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    @DisplayName("飞书：平台类型不符返回 404")
    void shouldFeishuPlatformMismatch() {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_WECOM);
        assertEquals(HttpStatus.NOT_FOUND,
            controller.feishuCallback(CODE, null, null, null, "{}").getStatusCode());
    }

    @Test
    @DisplayName("飞书：验签失败返回 401")
    void shouldFeishuSignatureFail() {
        FeishuCrypto crypto = mock(FeishuCrypto.class);
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        when(runtime.getFeishuCrypto()).thenReturn(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.feishuCallback(CODE, "sig", "ts", "nonce", "{}").getStatusCode());
    }

    @Test
    @DisplayName("飞书：密文但未配置 Encrypt Key 返回 400")
    void shouldFeishuEncryptWithoutKey() {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        when(runtime.getFeishuCrypto()).thenReturn(null);
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.feishuCallback(CODE, null, null, null, "{\"encrypt\":\"x\"}").getStatusCode());
    }

    @Test
    @DisplayName("飞书：报文解析失败返回 400")
    void shouldFeishuParseFail() {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.feishuCallback(CODE, null, null, null, "not-json").getStatusCode());
    }

    @Test
    @DisplayName("飞书：URL 校验握手回显 challenge")
    void shouldFeishuUrlChallenge() {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        when(runtime.getVerificationToken()).thenReturn(null);
        ResponseEntity<String> r = controller.feishuCallback(CODE, null, null, null,
            "{\"type\":\"url_verification\",\"challenge\":\"abc\"}");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("{\"challenge\":\"abc\"}", r.getBody());
    }

    @Test
    @DisplayName("飞书：校验口令不匹配返回 401")
    void shouldFeishuChallengeTokenMismatch() {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        when(runtime.getVerificationToken()).thenReturn("expected");
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.feishuCallback(CODE, null, null, null,
                "{\"type\":\"url_verification\",\"token\":\"wrong\",\"challenge\":\"abc\"}").getStatusCode());
    }

    @Test
    @DisplayName("飞书：重复事件幂等静默 200")
    void shouldFeishuDuplicateEvent() {
        FeishuInboundMapper mapper = mock(FeishuInboundMapper.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        setUpFeishu(mapper, idempotency, null);
        when(idempotency.firstSeen(CODE + "|ev1")).thenReturn(false);
        ResponseEntity<String> r = controller.feishuCallback(CODE, null, null, null,
            "{\"header\":{\"event_id\":\"ev1\"}}");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("飞书：非文本消息静默 200")
    void shouldFeishuNonText() {
        FeishuInboundMapper mapper = mock(FeishuInboundMapper.class);
        setUpFeishu(mapper, mock(IdempotencyStore.class), null);
        when(mapper.map(any(JsonNode.class))).thenReturn(Optional.empty());
        ResponseEntity<String> r = controller.feishuCallback(CODE, null, null, null, "{}");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("飞书：防自环限流静默 200")
    void shouldFeishuLoopGuardDeny() {
        FeishuInboundMapper mapper = mock(FeishuInboundMapper.class);
        setUpFeishu(mapper, mock(IdempotencyStore.class), false);
        when(mapper.map(any(JsonNode.class))).thenReturn(Optional.of(dm()));
        assertEquals(HttpStatus.OK, controller.feishuCallback(CODE, null, null, null, "{}").getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("飞书：文本消息投递给入站服务")
    void shouldFeishuDispatch() {
        FeishuInboundMapper mapper = mock(FeishuInboundMapper.class);
        setUpFeishu(mapper, mock(IdempotencyStore.class), true);
        when(mapper.map(any(JsonNode.class))).thenReturn(Optional.of(dm()));
        ResponseEntity<String> r = controller.feishuCallback(CODE, null, null, null, "{}");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(inboundService).submit(eq(runtime), any(InboundMessage.class));
    }

    private void setUpFeishu(FeishuInboundMapper mapper, IdempotencyStore idempotency, Boolean allow) {
        BotLoopGuard guard = mock(BotLoopGuard.class);
        if (allow != null) {
            when(guard.allow(any())).thenReturn(allow);
        }
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_FEISHU);
        when(runtime.getFeishuCrypto()).thenReturn(null);
        when(runtime.getFeishuMapper()).thenReturn(mapper);
        when(runtime.getIdempotency()).thenReturn(idempotency);
        when(runtime.getBotLoopGuard()).thenReturn(guard);
    }

    // ---------------------------- 企业微信 ----------------------------

    @Test
    @DisplayName("企业微信：渠道未命中返回 404")
    void shouldWecomVerifyNotFound() {
        assertEquals(HttpStatus.NOT_FOUND,
            controller.wecomVerify(CODE, "s", "t", "n", "e").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：URL 校验签名不匹配返回 401")
    void shouldWecomVerifySignatureFail() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        setUpWecomCrypto(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.wecomVerify(CODE, "s", "t", "n", "e").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：URL 校验解密失败返回 400")
    void shouldWecomVerifyDecryptFail() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        setUpWecomCrypto(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("e")).thenThrow(new IllegalStateException("bad"));
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.wecomVerify(CODE, "s", "t", "n", "e").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：URL 校验成功回显明文")
    void shouldWecomVerifyOk() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        setUpWecomCrypto(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("e")).thenReturn("echo");
        ResponseEntity<String> r = controller.wecomVerify(CODE, "s", "t", "n", "e");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("echo", r.getBody());
    }

    @Test
    @DisplayName("企业微信：报文缺少 Encrypt 返回 400")
    void shouldWecomMissingEncrypt() {
        setUpWecomCrypto(mock(WeComCrypto.class));
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><A>1</A></xml>").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：消息回调签名不匹配返回 401")
    void shouldWecomCallbackSignatureFail() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        setUpWecomCrypto(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：消息回调解密失败返回 400")
    void shouldWecomCallbackDecryptFail() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        setUpWecomCrypto(crypto);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenThrow(new IllegalStateException("bad"));
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：重复消息幂等静默 200")
    void shouldWecomCallbackDuplicate() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        when(idempotency.firstSeen(CODE + "|m1")).thenReturn(false);
        setUpWecom(crypto, idempotency, null, null);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenReturn("<xml><MsgId>m1</MsgId></xml>");
        assertEquals(HttpStatus.OK,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("企业微信：报文解析异常静默 200")
    void shouldWecomCallbackMapFail() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        WeComInboundMapper mapper = mock(WeComInboundMapper.class);
        setUpWecom(crypto, mock(IdempotencyStore.class), mapper, null);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenReturn("<xml><Content>hi</Content></xml>");
        when(mapper.map(anyString())).thenThrow(new IllegalStateException("bad"));
        assertEquals(HttpStatus.OK,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：非文本归一化为空静默 200")
    void shouldWecomCallbackEmpty() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        WeComInboundMapper mapper = mock(WeComInboundMapper.class);
        setUpWecom(crypto, mock(IdempotencyStore.class), mapper, true);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenReturn("<xml><Content>hi</Content></xml>");
        when(mapper.map(anyString())).thenReturn(Optional.empty());
        assertEquals(HttpStatus.OK,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
    }

    @Test
    @DisplayName("企业微信：防自环限流静默 200")
    void shouldWecomCallbackLoopGuardDeny() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        WeComInboundMapper mapper = mock(WeComInboundMapper.class);
        setUpWecom(crypto, mock(IdempotencyStore.class), mapper, false);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenReturn("<xml><Content>hi</Content></xml>");
        when(mapper.map(anyString())).thenReturn(Optional.of(dm()));
        assertEquals(HttpStatus.OK,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("企业微信：文本消息投递给入站服务")
    void shouldWecomCallbackDispatch() {
        WeComCrypto crypto = mock(WeComCrypto.class);
        WeComInboundMapper mapper = mock(WeComInboundMapper.class);
        setUpWecom(crypto, mock(IdempotencyStore.class), mapper, true);
        when(crypto.verifySignature(any(), any(), any(), any())).thenReturn(true);
        when(crypto.decrypt("enc")).thenReturn("<xml><Content>hi</Content></xml>");
        when(mapper.map(anyString())).thenReturn(Optional.of(dm()));
        assertEquals(HttpStatus.OK,
            controller.wecomCallback(CODE, "s", "t", "n", "<xml><Encrypt>enc</Encrypt></xml>").getStatusCode());
        verify(inboundService).submit(eq(runtime), any(InboundMessage.class));
    }

    private void setUpWecomCrypto(WeComCrypto crypto) {
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_WECOM);
        when(runtime.getWeComCrypto()).thenReturn(crypto);
    }

    private void setUpWecom(WeComCrypto crypto, IdempotencyStore idempotency,
                            WeComInboundMapper mapper, Boolean allow) {
        setUpWecomCrypto(crypto);
        BotLoopGuard guard = mock(BotLoopGuard.class);
        if (allow != null) {
            when(guard.allow(any())).thenReturn(allow);
        }
        when(runtime.getIdempotency()).thenReturn(idempotency);
        when(runtime.getBotLoopGuard()).thenReturn(guard);
        when(runtime.getWeComMapper()).thenReturn(mapper);
    }

    // ---------------------------- GitHub ----------------------------

    @Test
    @DisplayName("GitHub：渠道未命中返回 404")
    void shouldGithubNotFound() {
        assertEquals(HttpStatus.NOT_FOUND,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", new byte[0]).getStatusCode());
    }

    @Test
    @DisplayName("GitHub：签名不匹配返回 401")
    void shouldGithubSignatureFail() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), null, null, null);
        when(verifier.verify(any(), any())).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", "{}".getBytes()).getStatusCode());
    }

    @Test
    @DisplayName("GitHub：非评论事件返回 204")
    void shouldGithubNonCommentEvent() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), null, null, null);
        when(verifier.verify(any(), any())).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT,
            controller.githubWebhook(CODE, "push", "d1", "sig", "{}".getBytes()).getStatusCode());
    }

    @Test
    @DisplayName("GitHub：报文解析失败返回 400")
    void shouldGithubParseFail() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), null, null, null);
        when(verifier.verify(any(), any())).thenReturn(true);
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", "not-json".getBytes()).getStatusCode());
    }

    @Test
    @DisplayName("GitHub：重复评论幂等静默 200")
    void shouldGithubDuplicate() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        IdempotencyStore idempotency = mock(IdempotencyStore.class);
        when(idempotency.firstSeen(CODE + "|55")).thenReturn(false);
        setUpGithub(verifier, idempotency, null, null, null);
        when(verifier.verify(any(), any())).thenReturn(true);
        String body = "{\"comment\":{\"id\":55}}";
        assertEquals(HttpStatus.OK,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", body.getBytes()).getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("GitHub：机器人自评被丢弃")
    void shouldGithubBotSelfComment() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), null, "bot", null);
        when(verifier.verify(any(), any())).thenReturn(true);
        String body = "{\"comment\":{\"user\":{\"login\":\"bot\"}}}";
        assertEquals(HttpStatus.OK,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", body.getBytes()).getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("GitHub：归一化为空静默 200")
    void shouldGithubMapperEmpty() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        GitHubInboundMapper mapper = mock(GitHubInboundMapper.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), mapper, null, true);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(mapper.map(anyString(), any(JsonNode.class))).thenReturn(Optional.empty());
        assertEquals(HttpStatus.OK,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", "{}".getBytes()).getStatusCode());
    }

    @Test
    @DisplayName("GitHub：防自环限流静默 200")
    void shouldGithubLoopGuardDeny() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        GitHubInboundMapper mapper = mock(GitHubInboundMapper.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), mapper, null, false);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(mapper.map(anyString(), any(JsonNode.class))).thenReturn(Optional.of(dm()));
        assertEquals(HttpStatus.OK,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", "{}".getBytes()).getStatusCode());
        verify(inboundService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("GitHub：评论事件投递给入站服务")
    void shouldGithubDispatch() {
        GitHubSignatureVerifier verifier = mock(GitHubSignatureVerifier.class);
        GitHubInboundMapper mapper = mock(GitHubInboundMapper.class);
        setUpGithub(verifier, mock(IdempotencyStore.class), mapper, null, true);
        when(verifier.verify(any(), any())).thenReturn(true);
        when(mapper.map(anyString(), any(JsonNode.class))).thenReturn(Optional.of(dm()));
        assertEquals(HttpStatus.OK,
            controller.githubWebhook(CODE, "issue_comment", "d1", "sig", "{}".getBytes()).getStatusCode());
        verify(inboundService).submit(eq(runtime), any(InboundMessage.class));
    }

    private void setUpGithub(GitHubSignatureVerifier verifier, IdempotencyStore idempotency,
                             GitHubInboundMapper mapper, String botLogin, Boolean allow) {
        BotLoopGuard guard = mock(BotLoopGuard.class);
        if (allow != null) {
            when(guard.allow(any())).thenReturn(allow);
        }
        when(registry.resolve(CODE)).thenReturn(runtime);
        when(runtime.getPlatformType()).thenReturn(AiChannel.PLATFORM_GITHUB);
        when(runtime.getGitHubVerifier()).thenReturn(verifier);
        when(runtime.getIdempotency()).thenReturn(idempotency);
        when(runtime.getGitHubMapper()).thenReturn(mapper);
        when(runtime.getGitHubBotLogin()).thenReturn(botLogin);
        when(runtime.getBotLoopGuard()).thenReturn(guard);
    }

}