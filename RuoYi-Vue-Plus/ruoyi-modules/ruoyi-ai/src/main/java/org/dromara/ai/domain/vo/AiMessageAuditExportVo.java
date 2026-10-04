package org.dromara.ai.domain.vo;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话消息审计导出视图对象
 *
 * @author BizBuddy
 */
@Data
@ExcelIgnoreUnannotated
public class AiMessageAuditExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "会话标题", index = 0)
    private String sessionTitle;

    @ExcelProperty(value = "智能体", index = 1)
    private String agentName;

    @ExcelProperty(value = "归属用户", index = 2)
    private String userName;

    @ExcelProperty(value = "角色", index = 3)
    private String roleText;

    @ExcelProperty(value = "消息正文", index = 4)
    private String content;

    @ExcelProperty(value = "状态", index = 5)
    private String statusText;

    @ExcelProperty(value = "失败原因", index = 6)
    private String errorMsg;

    @ExcelProperty(value = "工具调用次数", index = 7)
    private Integer toolCallCount;

    @ExcelProperty(value = "总 Token", index = 8)
    private Integer totalTokens;

    @ExcelProperty(value = "耗时(ms)", index = 9)
    private Long durationMs;

    @ExcelProperty(value = "时间", index = 10)
    private String createTime;

}
