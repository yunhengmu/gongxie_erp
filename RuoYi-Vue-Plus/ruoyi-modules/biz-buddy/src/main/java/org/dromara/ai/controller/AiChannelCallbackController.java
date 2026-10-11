package org.dromara.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.extensions.channel.feishu.FeishuInboundMapper;
import io.agentscope.extensions.channel.wecom.WeComInboundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.channel.AiChannelInboundService;
import org.dromara.ai.core.channel.AiChannelRuntime;
import org.dromara.ai.core.channel.AiChannelRuntimeRegistry;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 渠道入站回调 Controller（REQ-AI-24）
 *
 * <p>统一前缀 {@code /ai/channel/callback/{platform}/{channelCode}}。该前缀在
 * {@code security.excludes} 中**免登录**（外部平台无法携带平台登录态），
 * 安全边界由各平台验签 / 解密与渠道口令承担：</p>
 *
 * <ul>
 *   <li>飞书 {@code POST /feishu/{channelCode}}：{@code X-Lark-Signature} 校验（配置 Encrypt Key 时）+ AES 解密
 *       + URL 校验握手回显 + {@code header.event_id} 幂等 + 防自环；</li>
 *   <li>企业微信 {@code GET|POST /wecom/{channelCode}}：{@code msg_signature} SHA-1 校验 + AES 解密
 *       + {@code MsgId} 幂等 + 防自环；</li>
 *   <li>GitHub {@code POST /github/{channelCode}}：{@code X-Hub-Signature-256} HMAC 校验 +
 *       {@code comment.id} 幂等 + 机器人账号自环判定。</li>
 * </ul>
 *
 * <p>通过校验的文本消息交由 {@link AiChannelInboundService} 异步处理并**立即 200**，
 * 避免平台因超时重推。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/channel/callback")
public class AiChannelCallbackController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DocumentBuilderFactory DBF = newSafeDocumentBuilderFactory();

    private final AiChannelRuntimeRegistry runtimeRegistry;

    private final AiChannelInboundService inboundService;

    // -----------------------------------------------------------------
    //  飞书
    // -----------------------------------------------------------------

    /**
     * 飞书事件订阅回调（含 URL 校验握手）。
     *
     * @param channelCode 渠道编码
     * @param signature   X-Lark-Signature
     * @param timestamp   X-Lark-Request-Timestamp
     * @param nonce       X-Lark-Request-Nonce
     * @param rawBody     原始请求体
     * @return 应答
     */
    @PostMapping(value = "/feishu/{channelCode}",
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> feishuCallback(
        @PathVariable String channelCode,
        @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
        @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
        @RequestBody String rawBody) {
        AiChannelRuntime runtime = requireRuntime(channelCode, AiChannel.PLATFORM_FEISHU);
        if (runtime == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // 1. 验签：配置了 Encrypt Key 且带签名头时强制校验
        if (runtime.getFeishuCrypto() != null && signature != null
            && !runtime.getFeishuCrypto().verifySignature(signature, timestamp, nonce, rawBody)) {
            log.warn("飞书回调验签失败：channelCode={}", channelCode);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 2. 解析外层报文；带 encrypt 时先解密再解析
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(rawBody);
            if (envelope.has("encrypt")) {
                if (runtime.getFeishuCrypto() == null) {
                    log.warn("飞书回调为密文但未配置 Encrypt Key：channelCode={}", channelCode);
                    return ResponseEntity.badRequest().build();
                }
                envelope = MAPPER.readTree(runtime.getFeishuCrypto().decrypt(envelope.get("encrypt").asText()));
            }
        } catch (Exception e) {
            log.warn("飞书回调解密 / 解析失败：channelCode={}，原因={}", channelCode, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        // 3. URL 校验握手：回显 challenge（配置了 Verification Token 时先比对）
        Optional<String> challenge = FeishuInboundMapper.extractUrlChallenge(envelope);
        if (challenge.isPresent()) {
            String configured = runtime.getVerificationToken();
            String bodyToken = envelope.path("token").asText(null);
            if (StringUtils.isNotBlank(configured) && !configured.equals(bodyToken)) {
                log.warn("飞书回调校验口令不匹配：channelCode={}", channelCode);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            try {
                return ResponseEntity.ok(MAPPER.writeValueAsString(Map.of("challenge", challenge.get())));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        // 4. 幂等：重复 event_id 静默 200，让飞书停止重推
        Optional<String> eventId = FeishuInboundMapper.extractEventId(envelope);
        if (eventId.isPresent() && !runtime.getIdempotency().firstSeen(channelCode + "|" + eventId.get())) {
            log.debug("飞书回调重复事件被丢弃：channelCode={}, eventId={}", channelCode, eventId.get());
            return ResponseEntity.ok("{}");
        }
        // 5. 归一化：非文本 / 结构异常一律静默应答
        Optional<InboundMessage> inbound = runtime.getFeishuMapper().map(envelope);
        if (inbound.isEmpty()) {
            return ResponseEntity.ok("{}");
        }
        InboundMessage message = inbound.get();
        // 6. 防自环：按会话 peer 限流
        if (!runtime.getRateLimiter().allow(channelCode, message.peer().key())) {
            log.warn("飞书回调触发防自环限流：channelCode={}, peer={}", channelCode, message.peer().key());
            return ResponseEntity.ok("{}");
        }
        inboundService.submit(runtime, message);
        return ResponseEntity.ok("{}");
    }

    // -----------------------------------------------------------------
    //  企业微信
    // -----------------------------------------------------------------

    /**
     * 企业微信回调 URL 校验握手（回显解密后的 echostr）。
     *
     * @param channelCode 渠道编码
     * @param signature   msg_signature
     * @param timestamp   timestamp
     * @param nonce       nonce
     * @param echostr     加密的随机串
     * @return 明文 echostr
     */
    @GetMapping(value = "/wecom/{channelCode}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wecomVerify(
        @PathVariable String channelCode,
        @RequestParam("msg_signature") String signature,
        @RequestParam("timestamp") String timestamp,
        @RequestParam("nonce") String nonce,
        @RequestParam("echostr") String echostr) {
        AiChannelRuntime runtime = requireRuntime(channelCode, AiChannel.PLATFORM_WECOM);
        if (runtime == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!runtime.getWeComCrypto().verifySignature(signature, timestamp, nonce, echostr)) {
            log.warn("企业微信回调 URL 校验签名不匹配：channelCode={}", channelCode);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(runtime.getWeComCrypto().decrypt(echostr));
        } catch (Exception e) {
            log.warn("企业微信回调 URL 校验解密失败：channelCode={}，原因={}", channelCode, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 企业微信消息回调（XML 密文）。
     *
     * @param channelCode 渠道编码
     * @param signature   msg_signature
     * @param timestamp   timestamp
     * @param nonce       nonce
     * @param body        原始 XML 报文
     * @return 应答
     */
    @PostMapping(value = "/wecom/{channelCode}",
        consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wecomCallback(
        @PathVariable String channelCode,
        @RequestParam("msg_signature") String signature,
        @RequestParam("timestamp") String timestamp,
        @RequestParam("nonce") String nonce,
        @RequestBody String body) {
        AiChannelRuntime runtime = requireRuntime(channelCode, AiChannel.PLATFORM_WECOM);
        if (runtime == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String encrypt = extractEncrypt(body);
        if (encrypt == null) {
            log.warn("企业微信回调缺少 <Encrypt>：channelCode={}", channelCode);
            return ResponseEntity.badRequest().build();
        }
        if (!runtime.getWeComCrypto().verifySignature(signature, timestamp, nonce, encrypt)) {
            log.warn("企业微信回调签名不匹配：channelCode={}", channelCode);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String xml;
        try {
            xml = runtime.getWeComCrypto().decrypt(encrypt);
        } catch (Exception e) {
            log.warn("企业微信回调解密失败：channelCode={}，原因={}", channelCode, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        Optional<String> msgId = WeComInboundMapper.extractMsgId(xml);
        if (msgId.isPresent() && !runtime.getIdempotency().firstSeen(channelCode + "|" + msgId.get())) {
            log.debug("企业微信回调重复消息被丢弃：channelCode={}, msgId={}", channelCode, msgId.get());
            return ResponseEntity.ok("");
        }
        Optional<InboundMessage> inbound;
        try {
            inbound = runtime.getWeComMapper().map(xml);
        } catch (Exception e) {
            log.warn("企业微信回调报文解析失败：channelCode={}，原因={}", channelCode, e.getMessage());
            return ResponseEntity.ok("");
        }
        if (inbound.isEmpty()) {
            return ResponseEntity.ok("");
        }
        InboundMessage message = inbound.get();
        if (!runtime.getRateLimiter().allow(channelCode, message.peer().key())) {
            log.warn("企业微信回调触发防自环限流：channelCode={}, peer={}", channelCode, message.peer().key());
            return ResponseEntity.ok("");
        }
        inboundService.submit(runtime, message);
        return ResponseEntity.ok("");
    }

    // -----------------------------------------------------------------
    //  GitHub
    // -----------------------------------------------------------------

    /**
     * GitHub Webhook 回调（issue_comment / pull_request_review_comment）。
     *
     * @param channelCode 渠道编码
     * @param eventType   X-GitHub-Event
     * @param deliveryId  X-GitHub-Delivery
     * @param signature   X-Hub-Signature-256
     * @param rawBody     原始请求体
     * @return 应答
     */
    @PostMapping(value = "/github/{channelCode}",
        consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> githubWebhook(
        @PathVariable String channelCode,
        @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
        @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestBody byte[] rawBody) {
        AiChannelRuntime runtime = requireRuntime(channelCode, AiChannel.PLATFORM_GITHUB);
        if (runtime == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // 1. 签名校验（强制）
        if (!runtime.getGitHubVerifier().verify(signature, rawBody)) {
            log.warn("GitHub 回调签名不匹配：channelCode={}, delivery={}", channelCode, deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 2. 只处理评论类事件，其余 204 让 GitHub 停止重推
        if (!"issue_comment".equals(eventType) && !"pull_request_review_comment".equals(eventType)) {
            return ResponseEntity.noContent().build();
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("GitHub 回调报文解析失败：channelCode={}，原因={}", channelCode, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        // 3. 幂等：按 comment.id
        Optional<Long> commentId = io.agentscope.extensions.channel.github.GitHubInboundMapper.extractCommentId(payload);
        if (commentId.isPresent() && !runtime.getIdempotency().firstSeen(channelCode + "|" + commentId.get())) {
            log.debug("GitHub 回调重复评论被丢弃：channelCode={}, commentId={}", channelCode, commentId.get());
            return ResponseEntity.ok("{}");
        }
        // 4. 防自环：机器人自己的评论一律丢弃（按配置的机器人账号登录名比对）
        String botLogin = runtime.getGitHubBotLogin();
        String commenterLogin = extractCommenterLogin(payload);
        if (StringUtils.isNotBlank(botLogin) && botLogin.equalsIgnoreCase(commenterLogin)) {
            log.debug("GitHub 回调丢弃机器人自评：channelCode={}, login={}", channelCode, commenterLogin);
            return ResponseEntity.ok("{}");
        }
        // 5. 归一化
        Optional<InboundMessage> inbound = runtime.getGitHubMapper().map(eventType, payload);
        if (inbound.isEmpty()) {
            return ResponseEntity.ok("{}");
        }
        InboundMessage message = inbound.get();
        if (!runtime.getRateLimiter().allow(channelCode, message.peer().key())) {
            log.warn("GitHub 回调触发防自环限流：channelCode={}, peer={}", channelCode, message.peer().key());
            return ResponseEntity.ok("{}");
        }
        inboundService.submit(runtime, message);
        return ResponseEntity.ok("{}");
    }

    // -----------------------------------------------------------------
    //  内部工具
    // -----------------------------------------------------------------

    /**
     * 解析渠道运行期装配并校验平台类型。
     *
     * @param channelCode      渠道编码
     * @param expectedPlatform 期望的平台类型
     * @return 运行期装配；渠道不存在 / 已停用 / 平台类型不符时返回 null
     */
    private AiChannelRuntime requireRuntime(String channelCode, String expectedPlatform) {
        AiChannelRuntime runtime = runtimeRegistry.resolve(channelCode);
        if (runtime == null) {
            log.warn("渠道回调未命中启用渠道：channelCode={}", channelCode);
            return null;
        }
        if (!expectedPlatform.equals(runtime.getPlatformType())) {
            log.warn("渠道回调平台类型不符：channelCode={}, 期望={}, 实际={}",
                channelCode, expectedPlatform, runtime.getPlatformType());
            return null;
        }
        return runtime;
    }

    /**
     * 取 GitHub 评论作者登录名。
     *
     * @param payload Webhook 报文
     * @return 登录名，或 null
     */
    private static String extractCommenterLogin(JsonNode payload) {
        return payload.path("comment").path("user").path("login").asText(null);
    }

    /**
     * 取企业微信回调 XML 中的 {@code <Encrypt>} 密文。
     *
     * @param xml 原始 XML 报文
     * @return 密文，或 null
     */
    private static String extractEncrypt(String xml) {
        if (StringUtils.isBlank(xml)) {
            return null;
        }
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            NodeList list = root.getElementsByTagName("Encrypt");
            if (list == null || list.getLength() == 0) {
                return null;
            }
            Node node = list.item(0);
            String value = node != null ? node.getTextContent() : null;
            return StringUtils.isBlank(value) ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构造防 XML 外部实体注入的解析器工厂。
     *
     * @return 解析器工厂
     */
    private static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // 解析器不支持时跳过加固（保持与框架一致的最佳努力策略）
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

}