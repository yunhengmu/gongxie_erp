package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 记忆版本恢复参数。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryRestoreBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "智能体不能为空")
    private Long agentId;

    @NotNull(message = "用户不能为空")
    private Long userId;

    /**
     * 恢复模式：CONTENT 只恢复正文 / CONTENT_AND_WATERMARK 同时恢复合并水位
     */
    @NotBlank(message = "恢复模式不能为空")
    @Pattern(regexp = "^(CONTENT|CONTENT_AND_WATERMARK)$",
        message = "恢复模式只能是 CONTENT / CONTENT_AND_WATERMARK")
    private String mode = "CONTENT";

    @Size(max = 200, message = "备注不能超过 200 字")
    private String note;
}
