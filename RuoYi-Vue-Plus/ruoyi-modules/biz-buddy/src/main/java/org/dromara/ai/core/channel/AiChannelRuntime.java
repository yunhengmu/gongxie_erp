package org.dromara.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
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
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 单个渠道的运行期装配（REQ-AI-24）。
 *
 * <p>把本项目渠道配置翻译为 AgentScope channel 扩展的构件：验签 / 加解密、入站报文解析、
 * 出站发送、入站媒体下载，以及两个通用保障（{@link InboundEventDeduplicator} 幂等、
 * {@link AiChannelRateLimiter} 渠道级限流并兼作防自环）。二者均走 Redis，**多副本共享**。
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
     * 入站消息幂等（Redis，多副本共享）
     */
    private final InboundEventDeduplicator idempotency;

    /**
     * 渠道级入站限流（Redis，多副本共享，兼作防自环）
     */
    private final AiChannelRateLimiter rateLimiter;

    /**
     * 平台 OpenAPI 基地址（媒体下载用；GitHub 无媒体时为 API 基址）
     */
    private final String apiBase;

    /**
     * 飞书 tenant_access_token 提供者（非飞书渠道为 null）
     */
    private final FeishuAccessTokenProvider feishuTokenProvider;

    /**
     * 企业微信 access_token 提供者（非企微渠道为 null）
     */
    private final WeComAccessTokenProvider weComTokenProvider;

    /**
     * 钉钉 access_token 提供者（非钉钉渠道为 null）
     */
    private final DingTalkAccessTokenProvider dingTalkTokenProvider;

    /**
     * 钉钉机器人编码（媒体下载用；非钉钉渠道为 null）
     */
    private final String dingTalkRobotCode;

    /**
     * 平台 OpenAPI WebClient（媒体下载用；构造期不发请求）
     */
    private final WebClient apiClient;

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

    private AiChannelRuntime(AiChannel channel, Consumer<InboundMessage> inboundHandler,
                             InboundEventDeduplicator idempotency, AiChannelRateLimiter rateLimiter) {
        this.channelId = channel.getId();
        this.channelCode = channel.getChannelCode();
        this.platformType = channel.getPlatformType();
        this.agentId = channel.getAgentId();
        this.fingerprint = channel.getId() + "|" + channel.getUpdateTime();
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;

        String apiBase = StringUtils.trim(channel.getApiBase());
        FeishuCrypto feishuCryptoLocal = null;
        FeishuInboundMapper feishuMapperLocal = null;
        FeishuOutboundClient feishuOutboundLocal = null;
        FeishuAccessTokenProvider feishuTokenProviderLocal = null;
        WeComCrypto weComCryptoLocal = null;
        WeComInboundMapper weComMapperLocal = null;
        WeComOutboundClient weComOutboundLocal = null;
        WeComAccessTokenProvider weComTokenProviderLocal = null;
        GitHubSignatureVerifier gitHubVerifierLocal = null;
        GitHubInboundMapper gitHubMapperLocal = null;
        GitHubOutboundClient gitHubOutboundLocal = null;
        String gitHubBotLoginLocal = null;
        DingTalkInboundMapper dingTalkMapperLocal = null;
        DingTalkOutboundClient dingTalkOutboundLocal = null;
        DingTalkStreamClient dingTalkStreamLocal = null;
        DingTalkAccessTokenProvider dingTalkTokenProviderLocal = null;
        String dingTalkRobotCodeLocal = null;
        String resolvedBase = null;

        switch (channel.getPlatformType()) {
            case AiChannel.PLATFORM_FEISHU -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_FEISHU_API_BASE : apiBase;
                resolvedBase = base;
                if (StringUtils.isNotBlank(channel.getEncryptKey())) {
                    feishuCryptoLocal = new FeishuCrypto(channel.getEncryptKey());
                }
                feishuTokenProviderLocal =
                    new FeishuAccessTokenProvider(base, channel.getAppId(), channel.getAppSecret());
                feishuOutboundLocal = new FeishuOutboundClient(base, feishuTokenProviderLocal);
                feishuMapperLocal = new FeishuInboundMapper(channel.getChannelCode());
            }
            case AiChannel.PLATFORM_WECOM -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_WECOM_API_BASE : apiBase;
                resolvedBase = base;
                weComCryptoLocal = new WeComCrypto(
                    channel.getVerificationToken(), channel.getEncryptKey(), channel.getAppId());
                weComTokenProviderLocal =
                    new WeComAccessTokenProvider(base, channel.getAppId(), channel.getAppSecret());
                int agentIdValue = Integer.parseInt(StringUtils.trim(channel.getPlatformAgentId()));
                weComOutboundLocal = new WeComOutboundClient(base, weComTokenProviderLocal, agentIdValue);
                weComMapperLocal = new WeComInboundMapper(channel.getChannelCode(), channel.getAppId());
            }
            case AiChannel.PLATFORM_GITHUB -> {
                String base = StringUtils.isBlank(apiBase) ? DEFAULT_GITHUB_API_BASE : apiBase;
                resolvedBase = base;
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
                dingTalkTokenProviderLocal =
                    new DingTalkAccessTokenProvider(props.apiBase(), props.appKey(), props.appSecret());
                dingTalkOutboundLocal =
                    new DingTalkOutboundClient(props.apiBase(), dingTalkTokenProviderLocal, props.robotCode());
                dingTalkMapperLocal = new DingTalkInboundMapper(channel.getChannelCode(), props.appKey());
                dingTalkStreamLocal = new DingTalkStreamClient(props, this::onDingTalkPayload);
                resolvedBase = props.apiBase();
                dingTalkRobotCodeLocal = props.robotCode();
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
        this.feishuTokenProvider = feishuTokenProviderLocal;
        this.weComTokenProvider = weComTokenProviderLocal;
        this.dingTalkTokenProvider = dingTalkTokenProviderLocal;
        this.dingTalkRobotCode = dingTalkRobotCodeLocal;
        this.apiBase = resolvedBase;
        this.apiClient = resolvedBase == null ? null : WebClient.builder().baseUrl(resolvedBase).build();
        this.inboundHandler = inboundHandler;
    }

    /**
     * 依据解密后的渠道配置装配运行期构件。
     *
     * @param channel        已解密的渠道配置
     * @param inboundHandler 归一化入站消息的处理入口（钉钉长连接用它接收报文；Webhook 平台不使用）
     * @return 运行期装配
     */
    public static AiChannelRuntime build(AiChannel channel, Consumer<InboundMessage> inboundHandler,
                                         InboundEventDeduplicator idempotency,
                                         AiChannelRateLimiter rateLimiter) {
        return new AiChannelRuntime(channel, inboundHandler, idempotency, rateLimiter);
    }

    /**
     * 便捷装配（无 Spring 注入场景：单测与本地构造）。幂等与限流均为 Redis 静态工具的无状态包装，
     * 用默认配置构造即可，语义与注入版本一致；生产链路统一走 {@link AiChannelRuntimeRegistry} 注入的共享实例。
     *
     * @param channel        已解密的渠道配置
     * @param inboundHandler 归一化入站消息的处理入口
     * @return 运行期装配
     */
    public static AiChannelRuntime build(AiChannel channel, Consumer<InboundMessage> inboundHandler) {
        AiProperties properties = new AiProperties();
        return new AiChannelRuntime(channel, inboundHandler,
            new RedisInboundEventDeduplicator(properties), new AiChannelRateLimiter(properties));
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
        if (!rateLimiter.allow(channelCode, message.peer().key())) {
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 下载一条入站媒体消息的原始字节（REQ-AI-24 扩展：渠道多媒体）。
     *
     * <p>框架的入站 mapper 只把媒体消息映射为「标记文本 + {@link ChannelMediaMetadata} 元数据」，
     * 由本项目按平台凭据下载：飞书 message resource、企业微信 media/get（或直接用 PicUrl）、
     * 钉钉机器人消息文件。**下载失败一律返回 null**，由上层降级为标记文本，不阻断回答。</p>
     *
     * @param kind      媒体类型（{@link ChannelMediaMetadata} 的 image / file / audio / video）
     * @param messageId 平台消息 id（飞书资源下载必需；其余平台可空）
     * @param mediaId   平台素材 id（image_key / MediaId / downloadCode）
     * @param url       平台直接可取的 URL（企业微信 PicUrl；可空）
     * @param fileName  文件名（可空）
     * @param maxBytes  单文件字节上限（<=0 表示不限）
     * @return 媒体字节；不可用或失败时返回 null
     */
    public byte[] downloadMedia(String kind, String messageId, String mediaId, String url,
                                String fileName, int maxBytes) {
        try {
            byte[] data = switch (platformType) {
                case AiChannel.PLATFORM_FEISHU -> downloadFeishu(kind, messageId, mediaId);
                case AiChannel.PLATFORM_WECOM -> downloadWeCom(mediaId, url);
                case AiChannel.PLATFORM_DINGTALK -> downloadDingTalk(mediaId);
                default -> null;
            };
            if (data == null) {
                return null;
            }
            if (maxBytes > 0 && data.length > maxBytes) {
                log.warn("渠道媒体超过大小上限，跳过：channelCode={}, kind={}, bytes={}, max={}",
                    channelCode, kind, data.length, maxBytes);
                return null;
            }
            log.info("渠道媒体下载完成：channelCode={}, kind={}, fileName={}, bytes={}",
                channelCode, kind, fileName, data.length);
            return data;
        } catch (Exception e) {
            log.warn("渠道媒体下载失败：channelCode={}, kind={}, mediaId={}, 原因={}",
                channelCode, kind, mediaId, e.getMessage());
            return null;
        }
    }

    /**
     * 飞书：{@code GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=}。
     */
    private byte[] downloadFeishu(String kind, String messageId, String fileKey) {
        if (apiClient == null || feishuTokenProvider == null
            || StringUtils.isBlank(messageId) || StringUtils.isBlank(fileKey)) {
            return null;
        }
        String token = feishuTokenProvider.token().block(Duration.ofSeconds(10));
        String type = ChannelMediaMetadata.KIND_IMAGE.equals(kind) ? "image" : "file";
        return apiClient.get()
            .uri(uri -> uri.path("/open-apis/im/v1/messages/{mid}/resources/{key}")
                .queryParam("type", type).build(messageId, fileKey))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(20));
    }

    /**
     * 企业微信：优先用 {@code PicUrl} 直取，否则 {@code /cgi-bin/media/get}。
     */
    private byte[] downloadWeCom(String mediaId, String url) {
        if (StringUtils.isNotBlank(url)) {
            return WebClient.builder().build().get().uri(url)
                .retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(20));
        }
        if (apiClient == null || weComTokenProvider == null || StringUtils.isBlank(mediaId)) {
            return null;
        }
        String token = weComTokenProvider.token().block(Duration.ofSeconds(10));
        return apiClient.get()
            .uri(uri -> uri.path("/cgi-bin/media/get")
                .queryParam("access_token", token).queryParam("media_id", mediaId).build())
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(20));
    }

    /**
     * 钉钉：{@code POST /v1.0/robot/messageFiles/download} 换取 {@code downloadUrl} 后再 GET。
     */
    private byte[] downloadDingTalk(String downloadCode) throws Exception {
        if (apiClient == null || dingTalkTokenProvider == null || StringUtils.isBlank(downloadCode)) {
            return null;
        }
        String token = dingTalkTokenProvider.token().block(Duration.ofSeconds(10));
        String body = apiClient.post()
            .uri("/v1.0/robot/messageFiles/download")
            .header("x-acs-dingtalk-access-token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("downloadCode", downloadCode,
                "robotCode", StringUtils.defaultString(dingTalkRobotCode)))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(20));
        if (StringUtils.isBlank(body)) {
            return null;
        }
        String downloadUrl = MAPPER.readTree(body).path("downloadUrl").asText(null);
        if (StringUtils.isBlank(downloadUrl)) {
            return null;
        }
        return WebClient.builder().build().get().uri(downloadUrl)
            .retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(20));
    }

}