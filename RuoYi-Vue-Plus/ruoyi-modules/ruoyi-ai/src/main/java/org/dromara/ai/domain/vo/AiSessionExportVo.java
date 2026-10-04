package org.dromara.ai.domain.vo;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话导出视图对象（供会话管理页导出留档）
 *
 * @author BizBuddy
 */
@Data
@ExcelIgnoreUnannotated
public class AiSessionExportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "会话标题", index = 0)
    private String sessionTitle;

    @ExcelProperty(value = "智能体", index = 1)
    private String agentName;

    @ExcelProperty(value = "归属用户", index = 2)
    private String userName;

    @ExcelProperty(value = "归属部门", index = 3)
    private String deptName;

    @ExcelProperty(value = "消息条数", index = 4)
    private Integer messageCount;

    @ExcelProperty(value = "状态", index = 5)
    private String statusText;

    @ExcelProperty(value = "创建时间", index = 6)
    private String createTime;

    @ExcelProperty(value = "最后活跃时间", index = 7)
    private String lastActiveTime;

}
