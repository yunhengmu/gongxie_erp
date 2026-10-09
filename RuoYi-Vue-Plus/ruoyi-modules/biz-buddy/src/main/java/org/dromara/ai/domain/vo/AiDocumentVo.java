package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiDocument;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 知识库文档视图对象 ai_document
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiDocument.class)
public class AiDocumentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属知识库
     */
    private Long kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 文件后缀（小写，不含点）
     */
    private String fileSuffix;

    /**
     * OSS 文件 ID
     */
    private Long ossId;

    /**
     * 文件访问地址
     */
    private String fileUrl;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 解析后字符数
     */
    private Integer charCount;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 解析状态（0待解析 1解析中 2已解析 3解析失败）
     */
    private String parseStatus;

    /**
     * 解析失败原因
     */
    private String errorMsg;

    /**
     * 向量化状态（0待向量化 1向量化中 2已向量化 3向量化失败）
     */
    private String vectorStatus;

    /**
     * 向量化失败原因
     */
    private String vectorErrorMsg;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}