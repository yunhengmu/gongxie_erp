package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiDailyDigestBo;
import org.dromara.ai.domain.vo.AiDailyDigestVo;
import org.dromara.ai.service.IAiDailyDigestService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 平台运行日报 Controller（只读）
 *
 * <p>报表由「任务产出投递器」（定时任务）按日 upsert 写入 {@code ai_daily_digest}，
 * 本接口只提供查看：列表（日期 / 级别 / 五块快照）与按日期取正文。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/digest")
public class AiDailyDigestController extends BaseController {

    private final IAiDailyDigestService dailyDigestService;

    /**
     * 分页查询日报列表
     */
    @SaCheckPermission("ai:digest:list")
    @GetMapping("/list")
    public R<PageResult<AiDailyDigestVo>> list(@Validated(QueryGroup.class) AiDailyDigestBo bo,
                                               PageQuery pageQuery) {
        return R.ok(dailyDigestService.queryPageList(bo, pageQuery));
    }

    /**
     * 按日期获取日报详情
     *
     * @param digestDate 日报日期（yyyy-MM-dd）
     */
    @SaCheckPermission("ai:digest:query")
    @GetMapping("/{digestDate}")
    public R<AiDailyDigestVo> getInfo(@NotNull(message = "日报日期不能为空")
                                      @PathVariable("digestDate")
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate digestDate) {
        return R.ok(dailyDigestService.queryByDate(digestDate));
    }

}
