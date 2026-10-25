package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiDailyDigestBo;
import org.dromara.ai.domain.vo.AiDailyDigestVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.time.LocalDate;

/**
 * 平台运行日报 Service 接口（只读）。
 *
 * <p>报表由「任务产出投递器」按日 upsert 写入，本接口只提供查看。</p>
 *
 * @author BizBuddy
 */
public interface IAiDailyDigestService {

    /**
     * 分页查询日报列表（按日期倒序）。
     *
     * @param bo        查询条件（日期区间、结论级别）
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiDailyDigestVo> queryPageList(AiDailyDigestBo bo, PageQuery pageQuery);

    /**
     * 按日报日期查询详情。
     *
     * @param digestDate 日报日期
     * @return 日报详情
     */
    AiDailyDigestVo queryByDate(LocalDate digestDate);

}
