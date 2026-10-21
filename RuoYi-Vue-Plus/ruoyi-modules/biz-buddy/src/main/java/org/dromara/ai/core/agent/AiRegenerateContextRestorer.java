package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 重新生成前的模型上下文回退。
 *
 * <p>AgentScope 的会话状态按 {@code (userId, sessionId)} 整体存放，无法只摘掉某一轮；
 * 若直接在原上下文上重跑，模型会看到自己上一版回答，出现「如前所述」式的重复作答。
 * 本组件把上下文回退到目标轮**之前**，再由对话门面重新下发本轮用户消息。</p>
 *
 * <p>两级策略：</p>
 * <ol>
 *   <li><b>尾部截断</b>：上下文末尾正好是「本轮用户消息 + 旧助手回答」时原位删除，代价最低；</li>
 *   <li><b>兜底重放</b>：尾部形态不符合预期（例如上一轮异常未落上下文）时，清空上下文并按
 *       平台已落库的历史轮次重建纯文本上下文。</li>
 * </ol>
 *
 * <p>已知边界：会话摘要（summary）与长期记忆不参与回退，可能残留旧回答的概括；历史附件
 * 在兜底重放时只保留正文，不重建图片 / 文档内容块。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRegenerateContextRestorer {

    /**
     * 重放上下文时纳入的角色（系统消息不重放）
     */
    private static final String ROLE_USER = "user";

    private static final String ROLE_ASSISTANT = "assistant";

    private final AiMessageMapper messageMapper;

    /**
     * 回退模型上下文到目标轮之前。
     *
     * @param agent             会话对应的智能体实例（与后续生成共用同一实例，避免状态缓存错位）
     * @param userId            会话归属用户
     * @param sessionId         会话主键
     * @param targetUserMessageId 目标轮的用户消息主键
     * @return 是否回退成功；失败时调用方应拒绝重新生成，避免带污染上下文作答
     */
    public boolean restore(HarnessAgent agent, Long userId, Long sessionId, Long targetUserMessageId) {
        if (agent == null || userId == null || sessionId == null || targetUserMessageId == null) {
            return false;
        }
        ReActAgent delegate = agent.getDelegate();
        String uid = String.valueOf(userId);
        String sid = String.valueOf(sessionId);
        try {
            if (truncateTail(delegate, uid, sid, targetUserMessageId)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("重新生成上下文尾部截断失败，转兜底重放：sessionId={}, messageId={}, {}",
                sessionId, targetUserMessageId, e.getMessage());
        }
        try {
            return replayHistory(delegate, uid, sid, sessionId, targetUserMessageId);
        } catch (Exception e) {
            log.error("重新生成上下文兜底重放失败：sessionId={}, messageId={}",
                sessionId, targetUserMessageId, e);
            return false;
        }
    }

    /**
     * 尾部截断：删除上下文末尾的「旧助手回答 + 本轮用户消息」。
     *
     * <p>只删除尾部连续的助手 / 工具消息及其前面的那条用户消息；若尾部没有助手消息
     * （说明上一轮未正常落上下文）或文本对不上，返回 {@code false} 交给兜底重放。</p>
     *
     * @param delegate            框架智能体
     * @param uid                 用户标识
     * @param sid                 会话标识
     * @param targetUserMessageId 目标轮用户消息主键
     * @return 是否完成截断
     */
    private boolean truncateTail(ReActAgent delegate, String uid, String sid, Long targetUserMessageId) {
        AgentState state = delegate.getAgentState(uid, sid);
        List<Msg> context = state.contextMutable();
        int cursor = context.size() - 1;
        boolean sawAssistant = false;
        while (cursor >= 0 && context.get(cursor).getRole() != MsgRole.USER) {
            if (context.get(cursor).getRole() == MsgRole.ASSISTANT) {
                sawAssistant = true;
            }
            cursor--;
        }
        if (!sawAssistant || cursor < 0) {
            return false;
        }
        AiMessage target = messageMapper.selectById(targetUserMessageId);
        String expected = target == null ? null : StringUtils.trim(target.getContent());
        String actual = StringUtils.trim(context.get(cursor).getTextContent());
        // 安全合规可能对用户原话做掩码，文本不一致时走兜底重放而不是凭位置硬删
        if (StringUtils.isNotBlank(expected) && !StringUtils.equals(expected, actual)) {
            log.info("重新生成上下文尾部文本不一致，转兜底重放：sessionId={}", sid);
            return false;
        }
        context.subList(cursor, context.size()).clear();
        delegate.saveAgentState(uid, sid);
        return true;
    }

    /**
     * 兜底重放：清空上下文，按平台已落库的「目标轮之前」的消息重建纯文本上下文。
     *
     * @param delegate            框架智能体
     * @param uid                 用户标识
     * @param sid                 会话标识
     * @param sessionId           会话主键
     * @param targetUserMessageId 目标轮用户消息主键
     * @return 是否重放成功
     */
    private boolean replayHistory(ReActAgent delegate, String uid, String sid, Long sessionId,
                                  Long targetUserMessageId) {
        List<Msg> rebuilt = loadHistoryMessages(sessionId, targetUserMessageId);
        delegate.clearContext(uid, sid);
        AgentState state = delegate.getAgentState(uid, sid);
        state.contextMutable().addAll(rebuilt);
        delegate.saveAgentState(uid, sid);
        return true;
    }

    /**
     * 读取目标轮之前的已落库消息并转换为框架消息（仅正文，不含附件内容块）。
     *
     * @param sessionId           会话主键
     * @param targetUserMessageId 目标轮用户消息主键
     * @return 框架消息列表
     */
    private List<Msg> loadHistoryMessages(Long sessionId, Long targetUserMessageId) {
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiMessage::getSessionId, sessionId);
        lqw.lt(AiMessage::getId, targetUserMessageId);
        lqw.orderByAsc(AiMessage::getId);
        List<AiMessage> history = messageMapper.selectList(lqw);
        List<Msg> messages = new ArrayList<>();
        for (AiMessage item : history) {
            if (StringUtils.isBlank(item.getContent())) {
                continue;
            }
            MsgRole role = ROLE_USER.equals(item.getRole()) ? MsgRole.USER
                : ROLE_ASSISTANT.equals(item.getRole()) ? MsgRole.ASSISTANT : null;
            if (role == null) {
                continue;
            }
            messages.add(Msg.builder().role(role).textContent(item.getContent()).build());
        }
        return messages;
    }

}
