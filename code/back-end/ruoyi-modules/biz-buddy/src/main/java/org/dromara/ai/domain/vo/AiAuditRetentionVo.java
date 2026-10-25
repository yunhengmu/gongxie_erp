package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计与运行数据保留策略视图。
 *
 * <p>{@code pending} 为各表**当前超期行数**（按保留天数算出的截止时间统计），供页面在清理前展示体量；
 * {@code purged} 仅在执行清理后回带（每表实际删除行数），查询时为 {@code null}。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiAuditRetentionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 保留天数（0＝不限，不清理）
     */
    private int retentionDays;

    /**
     * 保留截止时间（早于该时间的行会被清理；不限时为 null）
     */
    private String cutoff;

    /**
     * 各表当前超期行数：键＝表名，值＝行数
     */
    private Map<String, Long> pending = new LinkedHashMap<>();

    /**
     * 本次清理各表实际删除行数（未执行清理时为 null）
     */
    private Map<String, Long> purged;

}
