package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 知识库文档切片对象 ai_chunk
 *
 * <p>切片随文档重建，采用物理删除，故不设逻辑删除标志。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_chunk")
public class AiChunk extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属知识库
     */
    private Long kbId;

    /**
     * 所属文档
     */
    private Long docId;

    /**
     * 切片序号（从 0 开始）
     */
    private Integer chunkIndex;

    /**
     * 切片正文
     */
    private String content;

    /**
     * 字符数
     */
    private Integer charCount;

    /**
     * 向量库中的主键
     */
    private String vectorId;

    /**
     * 章节路径（各级标题用 {@code >} 连接，如 第2章 > 2.3 考勤）
     */
    private String headingPath;

    /**
     * 切片起始页码（PDF 等分页文档）
     */
    private Integer pageNo;

    /**
     * 切片在文档纯文本中的起始字符下标（含）
     */
    private Integer charStart;

    /**
     * 切片在文档纯文本中的结束字符下标（不含）
     */
    private Integer charEnd;

}