package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库检索命中结果视图对象。
 *
 * @author BizBuddy
 */
@Data
public class AiRetrievalHitVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 切片序号
     */
    private Integer chunkIndex;

    /**
     * 相似度得分（COSINE，越大越相关）
     */
    private Double score;

    /**
     * 切片正文
     */
    private String content;

    /**
     * 来源文档主键
     */
    private Long docId;

    /**
     * 来源文档名称
     */
    private String docName;

}