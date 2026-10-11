package org.dromara.ai.core.agent;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiAttachmentAssembler} 的纯单元测试（不碰真实 OSS / 模型 / 数据库）。
 *
 * <p>覆盖：无附件短路、文档块先于图片块、文档超长截断、图片 Base64 与 MIME 回落、
 * 图片读取失败降级，以及 user message 的内容块组合。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("附件多模态装配")
class AiAttachmentAssemblerTest {

    private static final Long MESSAGE_ID = 1L;
    private static final Long OSS_ID = 9L;

    @Mock
    private IAiChatAttachmentService attachmentService;

    @Mock
    private AiProperties aiProperties;

    private AiAttachmentAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new AiAttachmentAssembler(attachmentService, aiProperties);
    }

    @Test
    @DisplayName("无附件：返回空列表且不读 OSS、不读配置")
    void shouldReturnEmptyWhenNoAttachment() {
        when(attachmentService.listByMessageId(MESSAGE_ID)).thenReturn(List.of());

        assertEquals(List.of(), assembler.buildBlocks(message()));

        verify(attachmentService, never()).downloadBytes(anyLong());
        verify(aiProperties, never()).getChat();
    }

    @Test
    @DisplayName("文档块排在图片块之前，且带「上传文档」前缀")
    void shouldPlaceDocumentBlocksBeforeImageBlocks() {
        when(attachmentService.listByMessageId(MESSAGE_ID))
            .thenReturn(List.of(image(null), document("doc.txt", "正文")));
        when(aiProperties.getChat()).thenReturn(chatWithInlineMax(20000));
        when(attachmentService.downloadBytes(OSS_ID)).thenReturn(new byte[] {1, 2, 3});

        List<ContentBlock> blocks = assembler.buildBlocks(message());

        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertInstanceOf(ImageBlock.class, blocks.get(1));
        assertEquals("【上传文档：doc.txt】\n<untrusted>\n正文\n</untrusted>", ((TextBlock) blocks.get(0)).getText());
    }

    @Test
    @DisplayName("图片 MIME 为空：回落到 image/png，且 Base64 编码字节")
    void shouldFallbackToPngAndEncodeBase64() {
        when(attachmentService.listByMessageId(MESSAGE_ID)).thenReturn(List.of(image(null)));
        when(aiProperties.getChat()).thenReturn(chatWithInlineMax(20000));
        when(attachmentService.downloadBytes(OSS_ID)).thenReturn(new byte[] {1, 2, 3});

        ImageBlock block = (ImageBlock) assembler.buildBlocks(message()).get(0);
        Base64Source source = (Base64Source) block.getSource();
        assertEquals("image/png", source.getMediaType());
        assertEquals("AQID", source.getData());
    }

    @Test
    @DisplayName("图片读取失败：降级为文本占位且不抛出")
    void shouldDegradeToTextPlaceholderWhenImageFails() {
        when(attachmentService.listByMessageId(MESSAGE_ID)).thenReturn(List.of(image("image/jpeg")));
        when(aiProperties.getChat()).thenReturn(chatWithInlineMax(20000));
        when(attachmentService.downloadBytes(OSS_ID)).thenThrow(new IllegalStateException("oss down"));

        List<ContentBlock> blocks = assembler.buildBlocks(message());

        assertInstanceOf(TextBlock.class, blocks.get(0));
        assertTrue(((TextBlock) blocks.get(0)).getText().contains("加载失败，已跳过"));
    }

    @Test
    @DisplayName("文档超长：按上限保留首尾、中段省略")
    void shouldTruncateLongDocument() {
        when(attachmentService.listByMessageId(MESSAGE_ID))
            .thenReturn(List.of(document("doc.txt", "0123456789abcdef")));
        when(aiProperties.getChat()).thenReturn(chatWithInlineMax(10));

        String text = ((TextBlock) assembler.buildBlocks(message()).get(0)).getText();

        assertEquals("【上传文档：doc.txt】\n<untrusted>\n01234\n……（文档过长，中段已省略）……\nbcdef\n</untrusted>", text);
    }

    @Test
    @DisplayName("buildUserMsg：空内容+空块为 0 块，文字在前附件在后")
    void shouldBuildUserMsgWithTextBeforeAttachments() {
        assertEquals(0, assembler.buildUserMsg("  ", null).getContent().size());

        Msg onlyText = assembler.buildUserMsg("你好", null);
        assertEquals(1, onlyText.getContent().size());
        assertEquals("你好", ((TextBlock) onlyText.getContent().get(0)).getText());

        TextBlock attachment = TextBlock.builder().text("附件文本").build();
        Msg mixed = assembler.buildUserMsg("你好", List.of(attachment));
        assertEquals(2, mixed.getContent().size());
        assertEquals("你好", ((TextBlock) mixed.getContent().get(0)).getText());
        assertEquals("附件文本", ((TextBlock) mixed.getContent().get(1)).getText());
    }

    // ---------------- fixtures ----------------

    private static AiMessage message() {
        AiMessage message = new AiMessage();
        message.setId(MESSAGE_ID);
        return message;
    }

    private static AiMessageAttachment document(String fileName, String parsedContent) {
        AiMessageAttachment attachment = new AiMessageAttachment();
        attachment.setId(2L);
        attachment.setFileType(AiMessageAttachment.TYPE_DOCUMENT);
        attachment.setFileName(fileName);
        attachment.setParsedContent(parsedContent);
        return attachment;
    }

    private static AiMessageAttachment image(String mimeType) {
        AiMessageAttachment attachment = new AiMessageAttachment();
        attachment.setId(3L);
        attachment.setFileType(AiMessageAttachment.TYPE_IMAGE);
        attachment.setFileName("pic.png");
        attachment.setMimeType(mimeType);
        attachment.setOssId(OSS_ID);
        return attachment;
    }

    private static AiProperties.Chat chatWithInlineMax(int max) {
        AiProperties.Chat chat = new AiProperties.Chat();
        chat.setDocumentInlineMaxChars(max);
        return chat;
    }
}
