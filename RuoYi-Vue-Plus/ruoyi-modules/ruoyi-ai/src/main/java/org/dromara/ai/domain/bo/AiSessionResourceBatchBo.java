package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 会话级资源集批量操作参数（REQ-AI-32）：增 / 删共用同一请求体。
 *
 * @author BizBuddy
 */
@Data
public class AiSessionResourceBatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源项集合
     */
    private List<AiSessionResourceItemBo> items;

}