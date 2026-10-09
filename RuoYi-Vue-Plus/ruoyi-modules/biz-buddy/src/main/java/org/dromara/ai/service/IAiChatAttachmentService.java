package org.dromara.ai.service;

import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * AI 对话附件 Service 接口（聊天上传的图片 / 文档）
 *
 * @author BizBuddy
 */
public interface IAiChatAttachmentService {

    /**
     * 登记一个已上传到对象存储的附件：校验类型与大小，文档解析为纯文本后落库（暂存态，未绑定消息）。
     *
     * @param sessionId 会话主键
     * @param bo        附件登记参数（ossId + 文件名）
     * @return 附件视图对象
     */
    AiChatAttachmentVo register(Long sessionId, AiChatAttachmentBo bo);

    /**
     * 将暂存附件绑定到用户消息（发送消息时调用）。
     *
     * @param sessionId     会话主键
     * @param messageId     用户消息主键
     * @param attachmentIds 附件主键集合（可空）
     * @return 绑定后的附件实体列表（按主键升序）
     */
    List<AiMessageAttachment> bindToMessage(Long sessionId, Long messageId, List<Long> attachmentIds);

    /**
     * 查询某条用户消息的附件实体（按主键升序）。
     *
     * @param messageId 用户消息主键
     * @return 附件实体列表
     */
    List<AiMessageAttachment> listByMessageId(Long messageId);

    /**
     * 批量查询若干消息的附件视图（供消息列表回显，按消息主键分组，附件按主键升序）。
     *
     * @param messageIds 消息主键集合
     * @return 消息主键 → 附件视图列表
     */
    Map<Long, List<AiChatAttachmentVo>> listVoMapByMessageIds(Collection<Long> messageIds);

    /**
     * 下载附件字节（装配期把图片转 Base64 或复用解析产物时使用）。
     *
     * @param ossId 对象存储主键
     * @return 文件字节
     */
    byte[] downloadBytes(Long ossId);

    /**
     * 删除一个尚未发送的暂存附件（用户移除 chip 时调用）。
     *
     * @param sessionId    会话主键
     * @param attachmentId 附件主键
     */
    void deletePending(Long sessionId, Long attachmentId);

}