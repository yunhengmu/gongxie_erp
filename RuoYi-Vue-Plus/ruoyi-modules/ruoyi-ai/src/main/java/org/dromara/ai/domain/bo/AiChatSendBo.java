package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话发送业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiChatSendBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户输入的对话内容
     */
    @NotBlank(message = "对话内容不能为空")
    private String content;

}
