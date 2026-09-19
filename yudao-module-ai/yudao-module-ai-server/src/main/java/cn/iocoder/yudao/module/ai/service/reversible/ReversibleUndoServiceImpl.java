package cn.iocoder.yudao.module.ai.service.reversible;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.dal.dataobject.chat.AiChatMessageDO;
import cn.iocoder.yudao.module.ai.dal.dataobject.reversible.ReversibleLedgerDO;
import cn.iocoder.yudao.module.ai.dal.mysql.reversible.ReversibleLedgerMapper;
import cn.iocoder.yudao.module.ai.framework.reversible.core.ReversibleTool;
import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContext;
import cn.iocoder.yudao.module.ai.framework.reversible.core.enums.ReversibleStatusEnum;
import cn.iocoder.yudao.module.ai.service.chat.AiChatMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.ai.enums.ErrorCodeConstants.*;

/**
 * 可撤回操作 Service 实现
 *
 * 校验五道闸门（顺序固定）：归一化 → 声明 → 归属 → 时间 → 状态 → 因果。
 * 冲账逐帧独立事务提交，中途失败立即停止并转人工，不自动重试剩余帧。
 *
 * 注意：本类<b>不能</b>加 {@code @Transactional}——
 * 每一条账本帧的冲账都必须是独立事务，失败不影响已冲成功的帧。
 *
 * @author yudao
 */
@Service
@Slf4j
public class ReversibleUndoServiceImpl implements ReversibleUndoService {

    @Resource
    private ReversibleLedgerMapper ledgerMapper;
    @Resource
    private AiChatMessageService chatMessageService;
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 冲账专用事务模板：REQUIRES_NEW，每条帧独立提交
     */
    private final TransactionTemplate reverseTransactionTemplate;

    public ReversibleUndoServiceImpl(PlatformTransactionManager transactionManager) {
        this.reverseTransactionTemplate = new TransactionTemplate(transactionManager);
        this.reverseTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void undo(Long messageId, Long userId, String reason) {
        String safeReason = StrUtil.maxLength(reason, 200);

        // ========== 闸门 0：归一化，把 assistant 消息换成它的 user 消息 ==========
        AiChatMessageDO userMessage = locateUserMessage(messageId);
        Long turnMessageId = userMessage.getId();
        Long conversationId = userMessage.getConversationId();

        // ========== 闸门 2：归属 ==========
        if (ObjUtil.notEqual(userMessage.getUserId(), userId)) {
            log.warn("[undo][用户({}) 尝试撤回他人消息({})，已记录]", userId, turnMessageId);
            throw exception(REVERSIBLE_NOT_OWNER);
        }

        // ========== 闸门 3：闭合判定 + 时间窗口 ==========
        List<AiChatMessageDO> messages = chatMessageService.getChatMessageListByConversationId(conversationId);
        AiChatMessageDO assistantMessage = messages.stream()
                .filter(message -> turnMessageId.equals(message.getReplyId()))
                .findFirst().orElse(null);
        // 闭合信号：assistant 行的 content 已被写入。流式在 doOnComplete 落库，同步在 sendMessage 落库
        if (assistantMessage == null || StrUtil.isEmpty(assistantMessage.getContent())) {
            throw exception(REVERSIBLE_TURN_NOT_CLOSED);
        }
        List<ReversibleLedgerDO> ledgers = ledgerMapper.selectListByMessageId(turnMessageId);
        // 窗口：取该消息下所有帧的最大值（帧上固化了入账时的配置）
        int windowMinutes = ledgers.stream()
                .map(ReversibleLedgerDO::getWindowMinutes)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        LocalDateTime closedTime = assistantMessage.getUpdateTime() != null
                ? assistantMessage.getUpdateTime() : assistantMessage.getCreateTime();
        if (windowMinutes > 0 && closedTime != null
                && closedTime.plusMinutes(windowMinutes).isBefore(LocalDateTime.now())) {
            throw exception(REVERSIBLE_EXPIRED);
        }

        // ========== 闸门 1：声明。没有账本帧 = 该消息只产生了对话，降级为「仅清记忆」 ==========
        if (CollUtil.isEmpty(ledgers)) {
            deleteMessagePair(userMessage, assistantMessage, userId);
            log.info("[undo][消息({}) 无账本帧，仅清理对话记忆]", turnMessageId);
            return;
        }

        // ========== 闸门 4：状态。只有全部 POSTED 才可冲 ==========
        if (ledgers.stream().anyMatch(ledger ->
                ObjUtil.notEqual(ledger.getStatus(), ReversibleStatusEnum.POSTED.getStatus()))) {
            throw exception(REVERSIBLE_STATUS_INVALID);
        }

        // ========== 闸门 5：因果。存在更晚的未冲账帧时拒绝，避免产生孤儿分录 ==========
        if (ledgerMapper.selectPostedCountAfterMessage(conversationId, turnMessageId) > 0) {
            throw exception(REVERSIBLE_HAS_LATER);
        }

        // ========== 逐帧逆序冲账（selectListByMessageId 已按 id 倒序） ==========
        for (ReversibleLedgerDO ledger : ledgers) {
            // 占位防并发：CAS 抢不到说明已被其它请求撤回，直接跳过
            if (ledgerMapper.casStatus(ledger.getId(), ReversibleStatusEnum.POSTED.getStatus(),
                    ReversibleStatusEnum.REVERSING.getStatus()) == 0) {
                log.info("[undo][账本帧({}) 状态已被抢占，跳过]", ledger.getId());
                continue;
            }
            try {
                String reversedVoucher = reverseTransactionTemplate.execute(status ->
                        applicationContext.getBean(ledger.getReverseBean(), ReversibleTool.class)
                                .reverse(buildContext(ledger, userId, safeReason)));
                ledgerMapper.updateById(new ReversibleLedgerDO().setId(ledger.getId())
                        .setStatus(ReversibleStatusEnum.REVERSED.getStatus())
                        .setReversedVoucher(reversedVoucher)
                        .setReverseReason(safeReason)
                        .setReversedTime(LocalDateTime.now()));
            } catch (Exception e) {
                // 立即停止，已冲的保留、未冲的不动，整条消息标记为部分失败
                log.error("[undo][账本帧({}) 冲账失败，停止后续冲账]", ledger.getId(), e);
                ledgerMapper.updateById(new ReversibleLedgerDO().setId(ledger.getId())
                        .setStatus(ReversibleStatusEnum.FAILED.getStatus())
                        .setReverseReason(safeReason)
                        .setReversedTime(LocalDateTime.now()));
                throw exception(REVERSIBLE_PARTIAL_FAILED);
            }
        }

        // ========== 清对话记忆 ==========
        // 逻辑删两条消息后，selectListByConversationId 不再返回它们，
        // filterContextMessages 自然不带入下一轮 Prompt。工具调用历史在 Java 侧未落库，无需处理。
        deleteMessagePair(userMessage, assistantMessage, userId);
        log.info("[undo][消息({}) 撤回完成，共冲账 {} 帧]", turnMessageId, ledgers.size());
    }

    /**
     * 定位到 user 消息：前端点的是回复气泡时传上来的是 assistant 行，需要归一化
     */
    private AiChatMessageDO locateUserMessage(Long messageId) {
        AiChatMessageDO message = chatMessageService.getChatMessage(messageId);
        if (message == null) {
            throw exception(REVERSIBLE_MESSAGE_NOT_EXISTS);
        }
        if (MessageType.ASSISTANT.getValue().equals(message.getType())) {
            if (message.getReplyId() == null) {
                throw exception(REVERSIBLE_MESSAGE_NOT_EXISTS);
            }
            message = chatMessageService.getChatMessage(message.getReplyId());
        }
        if (message == null || !MessageType.USER.getValue().equals(message.getType())) {
            throw exception(REVERSIBLE_MESSAGE_NOT_EXISTS);
        }
        return message;
    }

    private ReversibleContext buildContext(ReversibleLedgerDO ledger, Long userId, String reason) {
        return new ReversibleContext()
                .setLedgerId(ledger.getId())
                .setMessageId(ledger.getMessageId())
                .setToolName(ledger.getToolName())
                .setBizVoucher(ledger.getBizVoucher())
                .setRawInput(ledger.getToolInput())
                .setTenantId(TenantContextHolder.getTenantId())
                .setOperatorId(userId)
                .setReason(reason)
                .setReversedAt(LocalDateTime.now());
    }

    /**
     * 清理该消息在 Agent 侧的对话记忆
     *
     * 采用逻辑删（{@code @TableLogic}），既从后续 Prompt 上下文中消失，又保留审计痕迹。
     */
    private void deleteMessagePair(AiChatMessageDO userMessage, AiChatMessageDO assistantMessage, Long userId) {
        chatMessageService.deleteChatMessage(userMessage.getId(), userId);
        chatMessageService.deleteChatMessage(assistantMessage.getId(), userId);
    }

}
