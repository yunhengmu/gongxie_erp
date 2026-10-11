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
     * 切片序号（相邻合并后为组内最小切片号）
     */
    private Integer chunkIndex;

    /**
     * 相邻合并后覆盖的切片区间结束号（单条 / 未合并时为 null）
     */
    private Integer chunkIndexEnd;

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

    /**
     * 章节路径（各级标题用 {@code >} 连接，如 第2章 > 2.3 考勤；相邻合并后取起始切片）
     */
    private String headingPath;

    /**
     * 切片起始页码（PDF 等分页文档；相邻合并后取起始切片）
     */
    private Integer pageNo;

    /**
     * 切片在文档纯文本中的起始字符下标（含；相邻合并后取最小）
     */
    private Integer charStart;

    /**
     * 切片在文档纯文本中的结束字符下标（不含；相邻合并后取最大）
     */
    private Integer charEnd;

}