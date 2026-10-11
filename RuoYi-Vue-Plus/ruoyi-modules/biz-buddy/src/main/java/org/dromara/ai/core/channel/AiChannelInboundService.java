package org.dromara.ai.core.channel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiAgentTaskRunner;
import org.dromara.ai.core.agent.AiChatFacade;
import org.dromara.ai.domain.AiChannelThread;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.dto.AiTaskExecution;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 渠道入站消息的归一化处理（REQ-AI-24）。
 *
 * <p>处理链路：外部身份映射 →（未命中即拒绝并回复提示）→ 消息线程映射到会话
 * （未命中或会话已结束则新建会话并绑定）→ 落库用户消息 → 无人值守执行
 * （{@link AiChatFacade#executeUnattended}，写工具一律拒绝）→ 出站回复原渠道。</p>
 *
 * <p>Webhook 需立即应答，故 {@link #submit} 把整段处理提交到无人值守执行线程池，
 * 与定时任务共用同一池；执行结果落在 {@code ai_session} / {@code ai_message}，
 * 会话来源标记为 {@code channel}，可在会话管理页与审计页检索。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChannelInboundService {

    /**
     * 会话状态：进行中
     */
    private static final String SESSION_STATUS_ACTIVE = "0";

    /**
     * 无人值守会话标题的时间后缀格式
     */
    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 出站回复等待上限（秒）
     */
    private static final long REPLY_TIMEOUT_SECONDS = 20L;

    private static final String REPLY_UNMAPPED =
        "当前账号尚未绑定平台用户，无法执行。请联系管理员在「智能体 → 渠道接入 → 外部身份映射」中绑定后再试。";

    private static final String REPLY_USER_MISSING =
        "当前账号映射的平台用户不存在或已被停用，请联系管理员处理。";

    private static final String REPLY_AGENT_DISABLED =
        "该渠道绑定的智能体不存在或已停用，请联系管理员处理。";

    private static final String REPLY_EMPTY =
        "未识别到可用内容（当前支持文本、图片与常见文档）。";

    private final IAiChannelService channelService;

    private final IAiSessionService sessionService;

    private final AiChatFacade chatFacade;

    private final UserService userService;

    private final AiMessageMapper messageMapper;

    private final AiAgentTaskRunner taskRunner;

    /**
     * 入站多媒体内容块装配（图片 / 文件，REQ-AI-24 扩展）
     */
    private final AiChannelMediaAssembler mediaAssembler;

    /**
     * 提交一次入站消息处理（异步，不阻塞 Webhook 应答）。
     *
     * @param runtime 渠道运行期装配
     * @param inbound 归一化入站消息
     */
    public void submit(AiChannelRuntime runtime, InboundMessage inbound) {
        taskRunner.submit(() -> handle(runtime, inbound));
    }

    /**
     * 处理一次入站消息（执行链路）。
     *
     * @param runtime 渠道运行期装配
     * @param inbound 归一化入站消息
     */
    private void handle(AiChannelRuntime runtime, InboundMessage inbound) {
        OutboundAddress address = outboundAddress(runtime, inbound);
        // 入站内容：文本 + 多媒体内容块（图片 / 文件）；纯文本消息 text 非空、blocks 为空
        AiChannelMediaAssembler.ChannelContent content = mediaAssembler.assemble(runtime, inbound);
        if (content == null) {
            reply(runtime, address, REPLY_EMPTY);
            return;
        }
        String externalUserId = StringUtils.isNotBlank(inbound.senderId())
            ? inbound.senderId() : inbound.peer().id();
        Long platformUserId = channelService.resolvePlatformUserId(runtime.getChannelId(), externalUserId);
        if (platformUserId == null) {
            log.warn("渠道入站被拒（外部身份未映射）：channelCode={}, externalUserId={}",
                runtime.getChannelCode(), externalUserId);
            reply(runtime, address, REPLY_UNMAPPED);
            return;
        }
        UserDTO platformUser = userService.selectById(platformUserId);
        if (platformUser == null) {
            log.warn("渠道入站被拒（映射的平台用户不存在）：channelCode={}, platformUserId={}",
                runtime.getChannelCode(), platformUserId);
            reply(runtime, address, REPLY_USER_MISSING);
            return;
        }

        String threadKey = inbound.peer().key();
        AiSession session = resolveSession(runtime, threadKey, platformUser, externalUserId);
        if (session == null) {
            reply(runtime, address, REPLY_AGENT_DISABLED);
            return;
        }

        AiTaskExecution execution;
        try {
            AiMessage userMessage = sessionService.appendUserMessageUnattended(
                session.getId(), content.storedText());
            execution = chatFacade.executeUnattended(session, userMessage, platformUserId, content.blocks());
        } catch (Exception e) {
            log.error("渠道入站执行异常：channelCode={}, sessionId={}", runtime.getChannelCode(), session.getId(), e);
            reply(runtime, address, "执行异常：" + e.getMessage());
            return;
        }
        channelService.touchThread(runtime.getChannelId(), threadKey);
        reply(runtime, address, resolveReplyText(execution));
    }

    /**
     * 解析（或新建）该线程对应的会话：线程已绑定且会话进行中则续用，否则新建并重新绑定。
     *
     * @param runtime        渠道运行期装配
     * @param threadKey      线程标识
     * @param platformUser   映射到的平台用户
     * @param externalUserId 外部用户标识（用于会话标题）
     * @return 会话实体；智能体不可用等新建失败时返回 null
     */
    private AiSession resolveSession(AiChannelRuntime runtime, String threadKey,
                                     UserDTO platformUser, String externalUserId) {
        AiChannelThread thread = channelService.findThread(runtime.getChannelId(), threadKey);
        if (thread != null && thread.getSessionId() != null) {
            AiSession existing = loadSessionQuietly(thread.getSessionId());
            if (existing != null && SESSION_STATUS_ACTIVE.equals(existing.getStatus())) {
                return existing;
            }
        }
        try {
            AiSession created = sessionService.createUnattendedSession(runtime.getAgentId(),
                buildSessionTitle(runtime, externalUserId),
                platformUser.getUserId(), platformUser.getDeptId(), AiSession.SOURCE_CHANNEL);
            channelService.bindThread(runtime.getChannelId(), threadKey, created.getId());
            return created;
        } catch (Exception e) {
            log.error("渠道会话创建失败：channelCode={}, threadKey={}", runtime.getChannelCode(), threadKey, e);
            return null;
        }
    }

    /**
     * 读取会话（不存在/已删除时静默返回 null，随后走新建分支）。
     *
     * @param sessionId 会话主键
     * @return 会话实体，或 null
     */
    private AiSession loadSessionQuietly(Long sessionId) {
        try {
            return sessionService.getSession(sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 组装出站地址（格式与框架 {@code ChannelRouter} 一致：{@code channelCode:peerKind:peerId}）。
     *
     * @param runtime 渠道运行期装配
     * @param inbound 归一化入站消息
     * @return 出站地址
     */
    private OutboundAddress outboundAddress(AiChannelRuntime runtime, InboundMessage inbound) {
        Peer peer = inbound.peer();
        String to = runtime.getChannelCode() + ":" + peer.kind().name() + ":" + peer.id();
        String threadId = peer.kind().isThread() ? peer.id() : null;
        return new OutboundAddress(runtime.getChannelCode(), inbound.accountId(), to, threadId);
    }

    /**
     * 组装会话标题（渠道编码 + 外部身份 + 时间）。
     *
     * @param runtime        渠道运行期装配
     * @param externalUserId 外部用户标识
     * @return 会话标题
     */
    private String buildSessionTitle(AiChannelRuntime runtime, String externalUserId) {
        return runtime.getChannelCode() + " · " + externalUserId + " · "
            + LocalDateTime.now().format(TITLE_TIME_FORMAT);
    }

    /**
     * 取本轮执行的回复正文（成功取助手消息，失败给失败原因）。
     *
     * @param execution 执行结果
     * @return 回复正文
     */
    private String resolveReplyText(AiTaskExecution execution) {
        if (execution == null) {
            return "本轮执行未返回结果。";
        }
        if (!execution.success()) {
            return "执行失败：" + StringUtils.defaultString(execution.errorMsg());
        }
        AiMessage assistant = execution.messageId() == null ? null : messageMapper.selectById(execution.messageId());
        if (assistant == null || StringUtils.isBlank(assistant.getContent())) {
            return "（本轮无文本回答）";
        }
        return assistant.getContent();
    }

    /**
     * 出站回复（失败只告警：会话与消息已落库，不因外呼失败回滚）。
     *
     * @param runtime 渠道运行期装配
     * @param address 出站地址
     * @param text    回复正文
     */
    private void reply(AiChannelRuntime runtime, OutboundAddress address, String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        try {
            runtime.send(address, List.of(Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build()))
                .block(Duration.ofSeconds(REPLY_TIMEOUT_SECONDS));
        } catch (Exception e) {
            log.warn("渠道出站回复失败，channelCode={}：{}", runtime.getChannelCode(), e.getMessage());
        }
    }

}