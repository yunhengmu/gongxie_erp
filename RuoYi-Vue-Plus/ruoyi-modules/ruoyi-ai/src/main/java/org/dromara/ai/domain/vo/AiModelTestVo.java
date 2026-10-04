package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 模型连通性测试结果。
 *
 * @author BizBuddy
 */
@Data
public class AiModelTestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否连通
     */
    private Boolean success;

    /**
     * 结果说明（成功为模型返回摘要，失败为错误原因）
     */
    private String message;

    /**
     * 耗时（毫秒）
     */
    private Long elapsedMs;

}