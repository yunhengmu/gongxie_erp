package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 知识库文档对象 ai_document
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_document")
public class AiDocument extends BaseEntity {

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
     * 解析后的纯文本内容（用于在线预览）
     */
    private String content;

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
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}