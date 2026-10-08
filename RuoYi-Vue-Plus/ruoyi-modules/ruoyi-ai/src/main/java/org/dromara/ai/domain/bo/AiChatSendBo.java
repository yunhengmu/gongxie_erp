package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 对话发送业务对象
 *
 * <p>内容与附件**至少提供一个**：可只发文字、只发附件，或两者同时发（校验在服务端完成）。</p>
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
    private String content;

    /**
     * 随本轮消息发送的附件主键集合（上传后登记获得，可空）
     */
    private List<Long> attachmentIds;

}
