package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 知识库文档批量处理进度（轮询回执）
 *
 * @author BizBuddy
 */
@Data
public class AiDocumentBatchStatusVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档主键
     */
    private Long id;

    /**
     * 文档名称
     */
    private String docName;

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

}
