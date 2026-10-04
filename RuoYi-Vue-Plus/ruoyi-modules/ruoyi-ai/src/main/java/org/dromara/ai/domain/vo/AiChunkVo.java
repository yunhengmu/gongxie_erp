package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiChunk;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 知识库切片视图对象 ai_chunk
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiChunk.class)
public class AiChunkVo implements Serializable {

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
     * 创建时间
     */
    private LocalDateTime createTime;

}