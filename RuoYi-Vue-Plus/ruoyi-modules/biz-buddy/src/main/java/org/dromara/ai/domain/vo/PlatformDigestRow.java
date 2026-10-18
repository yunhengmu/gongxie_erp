package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 平台运行日报**扁平行**（Mapper 返回用的中间结构）。
 *
 * <p>五块统计各查一张表，每次只填自己那几列，其余保持 {@code null}；
 * 由 {@code PlatformDigestServiceImpl} 组装为嵌套的 {@link PlatformDigestVo}。
 * 列别名一律用**下划线**形式，交给 MyBatis 的驼峰映射规则。</p>
 *
 * <p><b>「有无数据」的探针约定</b>：每块的「失败 / 异常 / 合计」字段都用
 * {@code sum(...)} 聚合（**不加 coalesce**）—— 无匹配行时聚合结果为 {@code null}，
 * 有行但无失败时为 {@code 0}，因此可据此判定整块是「无数据」还是「有数据且为 0」。</p>
 *
 * @author BizBuddy
 */
@Data
public class PlatformDigestRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用量块（探针：{@code usageTotalTokens}，无行时为 null） */
    private Long usageTotalTokens;
    private Long usageRequestCount;
    private Long usageToolCallCount;
    private BigDecimal usageCost;

    /** 任务块（探针：{@code taskFailCount}） */
    private Long taskRunCount;
    private Long taskFailCount;

    /** 待办块（探针：{@code backlogOldest}） */
    private Long backlogCount;
    private String backlogTopNode;
    private java.time.LocalDateTime backlogOldest;

    /** 权限决策块（三者同表同查，任一为 null 即无数据） */
    private Long permAllowCount;
    private Long permAskCount;
    private Long permDenyCount;

    /** 登录块（探针：{@code loginFailCount}） */
    private Long loginSuccessCount;
    private Long loginFailCount;

    /** 登录异动块：当日「新 IP」数（近窗口内未出现过的 IP） */
    private Long loginAnomalyCount;
}
