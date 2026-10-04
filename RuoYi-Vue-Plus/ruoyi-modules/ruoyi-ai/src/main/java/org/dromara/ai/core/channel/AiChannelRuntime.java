package org.dromara.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.dingtalk.DingTalkAccessTokenProvider;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannelProperties;
import io.agentscope.extensions.channel.dingtalk.DingTalkInboundMapper;
import io.agentscope.extensions.channel.dingtalk.DingTalkOutboundClient;
import io.agentscope.extensions.channel.dingtalk.DingTalkStreamClient;
import io.agentscope.extensions.channel.feishu.FeishuAccessTokenProvider;
import io.agentscope.extensions.channel.feishu.FeishuCrypto;
import io.agentscope.extensions.channel.feishu.FeishuInboundMapper;
import io.agentscope.extensions.channel.feishu.FeishuOutboundClient;
import io.agentscope.extensions.channel.github.GitHubInboundMapper;
import io.agentscope.extensions.channel.github.GitHubOutboundClient;
import io.agentscope.extensions.channel.github.GitHubSignatureVerifier;
import io.agentscope.extensions.channel.wecom.WeComAccessTokenProvider;
import io.agentscope.extensions.channel.wecom.WeComCrypto;
import io.agentscope.extensions.channel.wecom.WeComInboundMapper;
import io.agentscope.extensions.channel.wecom.WeComOutboundClient;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 单个渠道的运行期装配（REQ-AI-24）。
 *
 * <p>把本项目渠道配置翻译为 AgentScope channel 扩展的构件：验签 / 加解密、入站报文解析、
 * 出站发送，以及两个通用保障（{@link IdempotencyStore} 幂等、{@link BotLoopGuard} 防自环）。
 * <b>不使用框架的 {@code Channel} / {@code HarnessGateway}</b>——执行链路走本项目无人值守通道，
 * 以便纳入会话、消息、工具明细、配额与链路追踪等治理面。</p>
 *
 * <p>构件本身无外部依赖、构造期不发网络请求，可随配置变更安全重建。
 * 钉钉为**长连接**（无回调地址），额外持有 {@link DingTalkStreamClient}，
 * 由 {@link #start()} / {@link #stop()} 管理连接生命周期。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Getter
public final class AiChannelRuntime {

    /**
     * 飞书开放平台默认 API 基地址
     */
    private static final String DEFAULT_FEISHU_API_BASE = "https://open.feishu.cn";

    /**
     * 企业微信默认 API 基地址
     */
    private static final String DEFAULT_WECOM_API_BASE = "https://qyapi.weixin.qq.com";

    /**
     * GitHub 默认 API 基地址
     */
    private static final String DEFAULT_GITHUB_API_BASE = "https://api.github.com";

    /**
     * 渠道主键（ai_channel.id）
     */
    private final Long channelId;

    /**
     * 渠道编码
     */
    private final String channelCode;

    /**
     * 平台类型
     */
    private final String platformType;

    /**
     * 默认智能体主键
     */
    private final Long agentId;

    /**
     * 配置指纹（渠道主键 + 更新时间），配置变更后据此重建
     */
    private final String fingerprint;

    /**
     * 入站消息幂等存储
     */
    private final IdempotencyStore idempotency;

    /**
     * 按 peer 的防自环限流
     */
    private final BotLoopGuard botLoopGuard;

    /**
     * 飞书：回调验签与解密
     */
    private final FeishuCrypto feishuCrypto;

    /**
     * 飞书：入站报文解析
     */
    private final FeishuInboundMapper feishuMapper;

    /**
     * 飞书：出站回复
     */
    private final FeishuOutboundClient feishuOutbound;

    /**
     * 企业微信：回调验签与解密
     */
    private final WeComCrypto weComCrypto;

    /**
     * 企业微信：入站报文解析
     */
    private final WeComInboundMapper weComMapper;

    /**
     * 企业微信：出站回复
     */
    private final WeComOutboundClient weComOutbound;

    /**
     * GitHub：Webhook 签名校验
     */
    private final GitHubSignatureVerifier gitHubVerifier;

    /**
     * GitHub：入站报文体解析
     */
    private final GitHubInboundMapper gitHubMapper;

    /**
     * GitHub：出站评论
     */
    private final GitHubOutboundClient gitHubOutbound;

    /**
     * GitHub：机器人账号登录名（用于自环判定；未配置时不做登录名比对）
     */
    private final String gitHubBotLogin;

    /**
     * 回调校验口令（明文；飞书 URL 校验握手用，企微已包在 {@link WeComCrypto} 内）
     */
    private final String verificationToken;

    /**
     * 钉钉：长连接入站报文解析
     */
    private final DingTalkInboundMapper dingTalkMapper;

    /**
     * 钉钉：出站发送（单聊 batchSend / 群聊 groupMessages/send）
     */
    private final DingTalkOutboundClient dingTalkOutbound;

    /**
     * 钉钉：长连接客户端（非钉钉渠道为 null）；由 {@link #start()} / {@link #stop()} 管理
     */
    private final DingTalkStreamClient dingTalkStream;

    /**
     * 归一化入站消息的处理入口（钉钉长连接用它接收报文；Webhook 平台不使用）
     */
    private final Consumer<InboundMessage> inboundHandler;

    private AiChannelRuntime(AiChannel channel, Consumer<InboundMessage> inboundHandler) {
        this.channelId = channel.getId();
        this.channelCode = channel.getChannelCode();
        this.platformType = channel.getPlatformType();
        this.agentId = channel.getAgentId();
        this.fingerprint = channel.getId() + "|" + channel.getUpdateTime();
        this.idempotency = new IdempotencyStore();
        this.botLoopGuard = new BotLoopGuard();

        String apiBase = StringUtils.trim(channel.getApiBase());
        FeishuCrypto feishuCryptoLocal = null;
        FeishuInboundMapper feishuMapperLocal = null;
        FeishuOutboundClient feishuOutboundLocal = null;
        WeComCrypto weComCryptoLocal = null;
        WeComInboundMapper weComMapperLocal = null;
        WeComOutboundClient weComOutboundLocal = null;
        GitHubSignatureVerifier gitHubVerifierLocal = null;
        GitHubInboundMapper gitHubMapperLocal = null;
        GitHubOutboundClient gitHubOutboundLocal = null;
        String gitHubBotLoginLocal = null;
        DingTalkInboundMapper dingTalkMapperLocal = null;
        DingTalkOutboundClient dingTalkOutboundLocal = null;
        DingTalkStreamClient dingTalkStreamLocal = null;

        switch (channel.getPlatformType()) {
            case AiChannel.PLATFORM_FEISHU -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_FEISHU_API_BASE : apiBase;
                if (StringUtils.isNotBlank(channel.getEncryptKey())) {
                    feishuCryptoLocal = new FeishuCrypto(channel.getEncryptKey());
                }
                FeishuAccessTokenProvider tokenProvider =
                    new FeishuAccessTokenProvider(base, channel.getAppId(), channel.getAppSecret());
                feishuOutboundLocal = new FeishuOutboundClient(base, tokenProvider);
                feishuMapperLocal = new FeishuInboundMapper(channel.getChannelCode());
            }
            case AiChannel.PLATFORM_WECOM -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_WECOM_API_BASE : apiBase;
                weComCryptoLocal = new WeComCrypto(
                    channel.getVerificationToken(), channel.getEncryptKey(), channel.getAppId());
                WeComAccessTokenProvider tokenProvider =
                    new WeComAccessTokenProvider(base, channel.getAppId(), channel.getAppSecret());
                int agentIdValue = Integer.parseInt(StringUtils.trim(channel.getPlatformAgentId()));
                weComOutboundLocal = new WeComOutboundClient(base, tokenProvider, agentIdValue);
                weComMapperLocal = new WeComInboundMapper(channel.getChannelCode(), channel.getAppId());
            }
            case AiChannel.PLATFORM_GITHUB -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_GITHUB_API_BASE : apiBase;
                gitHubVerifierLocal = new GitHubSignatureVerifier(channel.getWebhookSecret());
                gitHubOutboundLocal = new GitHubOutboundClient(base, channel.getApiToken());
                gitHubMapperLocal = new GitHubInboundMapper(channel.getChannelCode());
                gitHubBotLoginLocal = StringUtils.trim(channel.getPlatformAgentId());
            }
            case AiChannel.PLATFORM_DINGTALK -> {
                // 钉钉为长连接。注意：框架的 apiBase 只作用于 OpenAPI（accessToken / 出站），
                // **长连接注册地址是独立字段且默认硬编码为默认基址**，不随 apiBase 走；
                // 故此处显式派生为 <apiBase>/v1.0/gateway/connections/open（apiBase 留空则用框架默认）。
                String streamRegisterUrl = StringUtils.isBlank(apiBase)
                    ? null
                    : apiBase + "/v1.0/gateway/connections/open";
                DingTalkChannelProperties props = new DingTalkChannelProperties(
                    channel.getAppId(),
                    channel.getAppSecret(),
                    StringUtils.trim(channel.getPlatformAgentId()),
                    StringUtils.isBlank(apiBase) ? null : apiBase,
                    null,
                    streamRegisterUrl);
                DingTalkAccessTokenProvider tokenProvider =
                    new DingTalkAccessTokenProvider(props.apiBase(), props.appKey(), props.appSecret());
                dingTalkOutboundLocal =
                    new DingTalkOutboundClient(props.apiBase(), tokenProvider, props.robotCode());
                dingTalkMapperLocal = new DingTalkInboundMapper(channel.getChannelCode(), props.appKey());
                dingTalkStreamLocal = new DingTalkStreamClient(props, this::onDingTalkPayload);
            }
            default -> throw new IllegalArgumentException(
                "不支持的平台类型：" + channel.getPlatformType());
        }

        this.feishuCrypto = feishuCryptoLocal;
        this.feishuMapper = feishuMapperLocal;
        this.feishuOutbound = feishuOutboundLocal;
        this.weComCrypto = weComCryptoLocal;
        this.weComMapper = weComMapperLocal;
        this.weComOutbound = weComOutboundLocal;
        this.gitHubVerifier = gitHubVerifierLocal;
        this.gitHubMapper = gitHubMapperLocal;
        this.gitHubOutbound = gitHubOutboundLocal;
        this.gitHubBotLogin = gitHubBotLoginLocal;
        this.verificationToken = channel.getVerificationToken();
        this.dingTalkMapper = dingTalkMapperLocal;
        this.dingTalkOutbound = dingTalkOutboundLocal;
        this.dingTalkStream = dingTalkStreamLocal;
        this.inboundHandler = inboundHandler;
    }

    /**
     * 依据解密后的渠道配置装配运行期构件。
     *
     * @param channel        已解密的渠道配置
     * @param inboundHandler 归一化入站消息的处理入口（钉钉长连接用它接收报文；Webhook 平台不使用）
     * @return 运行期装配
     */
    public static AiChannelRuntime build(AiChannel channel, Consumer<InboundMessage> inboundHandler) {
        return new AiChannelRuntime(channel, inboundHandler);
    }

    /**
     * 启动渠道的运行期资源：钉钉建立长连接（其它平台为 no-op，由外部平台回调驱动）。
     */
    public void start() {
        if (dingTalkStream == null) {
            return;
        }
        dingTalkStream.start();
        log.info("渠道长连接已启动：channelCode={}", channelCode);
    }

    /**
     * 停止渠道的运行期资源并释放连接。
     */
    public void stop() {
        if (dingTalkStream == null) {
            return;
        }
        dingTalkStream.stop();
        log.info("渠道长连接已停止：channelCode={}", channelCode);
    }

    /**
     * 钉钉长连接入站报文处理：幂等（msgId）→ 归一化 → 防自环 → 交上层处理。
     *
     * <p>与三个 Webhook 平台同口径：非文本 / 结构异常静默丢弃，不落库、不执行。</p>
     *
     * @param payload 钉钉 Stream 下发的机器人消息报文
     */
    private void onDingTalkPayload(JsonNode payload) {
        Optional<String> msgId = DingTalkInboundMapper.extractMsgId(payload);
        if (msgId.isPresent() && !idempotency.firstSeen(channelCode + "|" + msgId.get())) {
            log.debug("钉钉长连接重复消息被丢弃：channelCode={}, msgId={}", channelCode, msgId.get());
            return;
        }
        Optional<InboundMessage> inbound = dingTalkMapper.map(payload);
        if (inbound.isEmpty()) {
            return;
        }
        InboundMessage message = inbound.get();
        if (!botLoopGuard.allow(message.peer().key())) {
            log.warn("钉钉长连接触发防自环限流：channelCode={}, peer={}", channelCode, message.peer().key());
            return;
        }
        if (inboundHandler == null) {
            log.warn("钉钉长连接未配置入站处理入口，丢弃消息：channelCode={}", channelCode);
            return;
        }
        inboundHandler.accept(message);
    }

    /**
     * 把回复推回外部平台（出站失败只告警，不影响已落库的会话结果）。
     *
     * @param address  出站地址（{@code channelCode:peerKind:peerId}）
     * @param messages 待发送消息
     * @return 发送完成信号
     */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        return switch (platformType) {
            case AiChannel.PLATFORM_FEISHU -> feishuOutbound.send(address, messages);
            case AiChannel.PLATFORM_WECOM -> weComOutbound.send(address, messages);
            case AiChannel.PLATFORM_GITHUB -> gitHubOutbound.send(address, messages);
            case AiChannel.PLATFORM_DINGTALK -> dingTalkOutbound.send(address, messages);
            default -> Mono.error(new IllegalStateException("不支持的平台类型：" + platformType));
        };
    }

}