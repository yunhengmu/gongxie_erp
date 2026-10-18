package org.dromara.ai.domain.bo;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 平台运行日报查询业务对象
 *
 * @author BizBuddy
 */
@Data
public class AiDailyDigestBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 日报日期起（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate beginDate;

    /**
     * 日报日期止（含）
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /**
     * 结论级别（0平稳 1需关注 2需处理）
     */
    private String level;

}
