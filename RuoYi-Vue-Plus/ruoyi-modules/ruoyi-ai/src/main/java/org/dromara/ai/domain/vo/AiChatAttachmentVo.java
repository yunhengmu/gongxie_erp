package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话附件视图对象
 *
 * @author BizBuddy
 */
@Data
public class AiChatAttachmentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 附件主键
     */
    private Long id;

    /**
     * 附件类型（image 图片 / document 文档）
     */
    private String fileType;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件后缀（小写，不含点）
     */
    private String fileSuffix;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 访问地址（图片预览 / 文档下载；对象存储不可达时为空）
     */
    private String url;

    /**
     * 文档解析后的字符数（图片为 0）
     */
    private Integer parsedCharCount;

}