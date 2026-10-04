package org.dromara.ai.domain.vo;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 用量导出视图对象
 *
 * @author BizBuddy
 */
@Data
@ExcelIgnoreUnannotated
public class AiUsageStatExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "统计日期", index = 0)
    private String statDate;

    @ExcelProperty(value = "智能体", index = 1)
    private String agentName;

    @ExcelProperty(value = "模型", index = 2)
    private String modelName;

    @ExcelProperty(value = "用户", index = 3)
    private String userName;

    @ExcelProperty(value = "输入 Token", index = 4)
    private Long promptTokens;

    @ExcelProperty(value = "输出 Token", index = 5)
    private Long completionTokens;

    @ExcelProperty(value = "总 Token", index = 6)
    private Long totalTokens;

    @ExcelProperty(value = "请求数", index = 7)
    private Integer requestCount;

    @ExcelProperty(value = "工具调用次数", index = 8)
    private Integer toolCallCount;

    @ExcelProperty(value = "费用(元)", index = 9)
    private BigDecimal cost;

}