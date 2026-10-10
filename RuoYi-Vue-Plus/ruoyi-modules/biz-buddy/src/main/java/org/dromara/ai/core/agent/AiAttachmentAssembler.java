package org.dromara.ai.core.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 对话附件的多模态装配（聊天上传的图片 / 文档）。
 *
 * <p>把一条用户消息的附件装配为 AgentScope 内容块：文档解析文本按上限截断后**内联为文本块**
 * （不切片、不向量化），图片读回字节转 **Base64 data URI** 图片块。附件内容随任务片段一并下发，
 * 故入口智能体与专家看到的是同一份材料。</p>
 *
 * <p>对象存储读取是外部边界：单张图片加载失败时降级为文本占位，避免整轮对话被打断。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAttachmentAssembler {

    private final IAiChatAttachmentService attachmentService;

    private final AiProperties aiProperties;

    /**
     * 构造本轮用户消息的附件内容块（文档解析文本内联 + 图片 Base64 图片块）。
     *
     * @param userMessage 已落库的用户消息
     * @return 附件内容块（无附件时为空列表）
     */
    List<ContentBlock> buildBlocks(AiMessage userMessage) {
        List<AiMessageAttachment> attachments = attachmentService.listByMessageId(userMessage.getId());
        if (attachments.isEmpty()) {
            return List.of();
        }
        int inlineMax = aiProperties.getChat().getDocumentInlineMaxChars();
        List<ContentBlock> blocks = new ArrayList<>();
        // 文档：解析文本内联为文本块（应在图片之前，作为可读事实）
        for (AiMessageAttachment attachment : attachments) {
            if (AiMessageAttachment.TYPE_DOCUMENT.equals(attachment.getFileType())) {
                blocks.add(TextBlock.builder()
                    .text("【上传文档：" + attachment.getFileName() + "】\n"
                        + truncateDocument(attachment.getParsedContent(), inlineMax))
                    .build());
            }
        }
        // 图片：读回字节转 Base64 data URI（模型侧按 image_url 下发）
        for (AiMessageAttachment attachment : attachments) {
            if (AiMessageAttachment.TYPE_IMAGE.equals(attachment.getFileType())) {
                blocks.add(buildImageBlock(attachment));
            }
        }
        return blocks;
    }

    /**
     * 用「用户文字 + 附件内容块」构造本轮 user message。
     *
     * @param content          用户输入文字（可空）
     * @param attachmentBlocks 附件内容块（可空）
     * @return 用户消息
     */
    Msg buildUserMsg(String content, List<ContentBlock> attachmentBlocks) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (StringUtils.isNotBlank(content)) {
            blocks.add(TextBlock.builder().text(content).build());
        }
        if (attachmentBlocks != null && !attachmentBlocks.isEmpty()) {
            blocks.addAll(attachmentBlocks);
        }
        return new UserMessage(blocks);
    }

    /**
     * 把一张图片附件转成 AgentScope 的图片内容块（Base64 来源）。
     *
     * <p>对象存储读取是外部边界：单张图片加载失败时降级为文本占位，避免整轮对话被打断。</p>
     *
     * @param attachment 图片附件
     * @return 图片内容块；加载失败时返回文本占位块
     */
    private ContentBlock buildImageBlock(AiMessageAttachment attachment) {
        try {
            byte[] bytes = attachmentService.downloadBytes(attachment.getOssId());
            String mediaType = StringUtils.isNotBlank(attachment.getMimeType())
                ? attachment.getMimeType() : "image/png";
            return ImageBlock.builder()
                .source(Base64Source.builder()
                    .data(Base64.getEncoder().encodeToString(bytes))
                    .mediaType(mediaType)
                    .build())
                .build();
        } catch (Exception e) {
            log.warn("图片附件加载失败，attachmentId={}", attachment.getId(), e);
            return TextBlock.builder()
                .text("【图片附件 " + attachment.getFileName() + " 加载失败，已跳过】")
                .build();
        }
    }

    /**
     * 按上限截断内联文档文本：保留首尾、中段省略（避免单个大文档撑爆模型上下文窗口）。
     *
     * @param text 文档全量文本
     * @param max  最大字符数（<=0 表示不截断）
     * @return 截断后的文本
     */
    private String truncateDocument(String text, int max) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        int head = max / 2;
        int tail = max - head;
        return text.substring(0, head)
            + "\n……（文档过长，中段已省略）……\n"
            + text.substring(text.length() - tail);
    }
}
