package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 回答参考来源视图对象：助手回答用到知识库时，随消息回显的一条出处。
 *
 * @author BizBuddy
 */
@Data
public class AiSourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 来源文档主键
     */
    private Long docId;

    /**
     * 来源文档名称
     */
    private String docName;

    /**
     * 章节路径（各级标题用 {@code >} 连接，可空）
     */
    private String headingPath;

    /**
     * 切片起始页码（PDF 等分页文档，可空）
     */
    private Integer pageNo;

    /**
     * 切片序号（可空）
     */
    private Integer chunkIndex;

    /**
     * 相似度得分（可空）
     */
    private Double score;

}
