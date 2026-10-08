package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.ai.domain.AiDocument;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 知识库文档业务对象 ai_document
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiDocument.class, reverseConvertGenerate = false)
public class AiDocumentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 所属知识库
     */
    @NotNull(message = "知识库不能为空", groups = {AddGroup.class})
    private Long kbId;

    /**
     * 文档名称
     */
    @NotBlank(message = "文档名称不能为空", groups = {AddGroup.class})
    private String docName;

    /**
     * 文件后缀（小写，不含点）
     */
    private String fileSuffix;

    /**
     * OSS 文件 ID（前端上传成功后回传）
     */
    @NotNull(message = "请先上传文件", groups = {AddGroup.class})
    private Long ossId;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 解析状态（0待解析 1解析中 2已解析 3解析失败）
     */
    private String parseStatus;

    /**
     * 备注
     */
    private String remark;

}