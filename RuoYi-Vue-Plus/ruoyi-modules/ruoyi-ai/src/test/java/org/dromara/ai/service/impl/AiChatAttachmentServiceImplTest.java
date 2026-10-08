package org.dromara.ai.service.impl;

import cn.hutool.http.HttpUtil;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiMessageAttachment;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.mapper.AiMessageAttachmentMapper;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.api.OssService;
import org.dromara.system.api.domain.OssDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiChatAttachmentServiceImpl} 单元测试：类型/大小校验、文档解析与附件绑定。
 */
@DisplayName("AI 对话附件 Service")
@ExtendWith(MockitoExtension.class)
class AiChatAttachmentServiceImplTest {

    private static final Long OSS_ID = 1L;

    @Mock
    private AiMessageAttachmentMapper baseMapper;

    @Mock
    private OssService ossService;

    private final AiProperties aiProperties = new AiProperties();

    private AiChatAttachmentServiceImpl service() {
        return new AiChatAttachmentServiceImpl(baseMapper, ossService, aiProperties);
    }

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @Test
    @DisplayName("不支持的格式被拒绝")
    void shouldRejectUnsupportedSuffix() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("exe")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service().register(1L, bo()));
        assertEquals("不支持的文件类型：.exe，图片仅支持 png / jpg / jpeg / gif / webp / heic / heif；"
            + "文档仅支持 PDF / docx / xlsx / pptx / 文本类文件", ex.getMessage());
    }

    @Test
    @DisplayName("图片超过大小上限被拒绝")
    void shouldRejectOversizeImage() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("png")));
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.png");
        byte[] oversized = new byte[(int) aiProperties.getChat().getImageMaxBytes() + 1];

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(oversized);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> service().register(1L, bo()));
            assertEquals("图片大小超过限制（最大 512KB）", ex.getMessage());
        }
    }

    @Test
    @DisplayName("文档登记解析文本并落库")
    void shouldRegisterDocumentAndParse() {
        when(ossService.selectByIds(String.valueOf(OSS_ID))).thenReturn(List.of(oss("txt")));
        // 下载走服务端内部地址（忽略对外域名），VO 回显走对外地址
        when(ossService.selectInternalUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://minio/x.txt");
        when(ossService.selectUrlByIds(String.valueOf(OSS_ID))).thenReturn("http://oss/x.txt");
        byte[] content = "你好智造".getBytes(StandardCharsets.UTF_8);

        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.downloadBytes(anyString())).thenReturn(content);
            AiChatAttachmentVo vo = service().register(1L, bo());

            assertEquals(AiMessageAttachment.TYPE_DOCUMENT, vo.getFileType());
            assertEquals(4, vo.getParsedCharCount());
            assertEquals("http://oss/x.txt", vo.getUrl());
        }
    }

    @Test
    @DisplayName("单条消息附件数超限被拒绝")
    void shouldRejectTooManyAttachments() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> service().bindToMessage(1L, 100L, List.of(1L, 2L, 3L, 4L, 5L, 6L)));
        assertEquals("单条消息最多携带 5 个附件", ex.getMessage());
    }

    @Test
    @DisplayName("跨会话附件被拒绝")
    void shouldRejectForeignSessionAttachment() {
        AiMessageAttachment foreign = new AiMessageAttachment();
        foreign.setId(1L);
        foreign.setSessionId(2L);
        when(baseMapper.selectByIds(any())).thenReturn(List.of(foreign));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service().bindToMessage(1L, 100L, List.of(1L)));
        assertEquals("附件不属于该会话，请重新上传", ex.getMessage());
    }

    private AiChatAttachmentBo bo() {
        AiChatAttachmentBo bo = new AiChatAttachmentBo();
        bo.setOssId(OSS_ID);
        bo.setFileName("x.txt");
        return bo;
    }

    private OssDTO oss(String suffix) {
        OssDTO dto = new OssDTO();
        dto.setOssId(OSS_ID);
        dto.setFileSuffix(suffix);
        dto.setOriginalName("x." + suffix);
        return dto;
    }

}