package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 上下文压缩策略业务对象（REQ-AI-21）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.chat.*}，页面保存后写入 Redis 覆盖默认值，
 * 并参与智能体装配指纹判定，保存后下一次对话即按新策略装配生效。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiCompactionConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用上下文压缩
     */
    private boolean enabled;

    /**
     * 触发压缩的消息条数阈值
     */
    @Min(value = 1, message = "触发条数必须大于 0")
    @Max(value = 10000, message = "触发条数不能超过 10000")
    private int triggerMessages;

    /**
     * 触发压缩的 Token 数阈值
     */
    @Min(value = 1000, message = "触发 Token 数必须不小于 1000")
    private int triggerTokens;

    /**
     * 压缩后保留的尾部消息条数
     */
    @Min(value = 1, message = "保留条数必须大于 0")
    @Max(value = 10000, message = "保留条数不能超过 10000")
    private int keepMessages;

    /**
     * 压缩前是否将完整历史落盘留痕
     */
    private boolean flushBeforeCompact;

    /**
     * 压缩前是否把历史卸载为工作区下的 jsonl 文件
     */
    private boolean offloadBeforeCompact;

    /**
     * 是否启用大工具结果卸载
     */
    private boolean toolResultEvictionEnabled;

    /**
     * 大工具结果卸载阈值（字符）
     */
    @Min(value = 1000, message = "卸载阈值必须不小于 1000 字符")
    private int toolResultEvictionMaxChars;

    /**
     * 大工具结果卸载后保留的预览字符数（首尾各一份）
     */
    @Min(value = 100, message = "预览字符数必须不小于 100")
    private int toolResultEvictionPreviewChars;

}