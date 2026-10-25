package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 知识库文档批量操作业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiDocumentBatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档主键集合
     */
    @NotEmpty(message = "请选择至少一个文档")
    private List<Long> ids;

}
