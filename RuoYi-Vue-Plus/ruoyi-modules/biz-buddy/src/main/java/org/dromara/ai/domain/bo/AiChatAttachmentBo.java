package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话附件登记业务对象。
 *
 * <p>文件本体先由框架 OSS 上传接口（{@code /resource/oss/upload}）落库，本对象只携带其 {@code ossId}
 * 与原始文件名，由服务端按会话归属校验、按类型/大小校验并（文档）解析。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiChatAttachmentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 对象存储主键（框架上传返回）
     */
    @NotNull(message = "文件不能为空")
    private Long ossId;

    /**
     * 原始文件名（用于展示与后缀判定，可空时回退 OSS 记录）
     */
    private String fileName;

}