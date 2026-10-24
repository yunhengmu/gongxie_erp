package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgentTaskRun;
import org.dromara.ai.domain.AiDailyDigest;
import org.dromara.ai.domain.bo.AiDailyDigestBo;
import org.dromara.ai.domain.vo.AiDailyDigestVo;
import org.dromara.ai.mapper.AiAgentTaskRunMapper;
import org.dromara.ai.mapper.AiDailyDigestMapper;
import org.dromara.ai.service.IAiDailyDigestService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台运行日报 Service 业务层处理（只读）
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiDailyDigestServiceImpl implements IAiDailyDigestService {

    private final AiDailyDigestMapper dailyDigestMapper;

    /**
     * 执行记录 Mapper：读取侧按 {@code session_id} 关联「来源任务执行」（写入期解析不到）
     */
    private final AiAgentTaskRunMapper taskRunMapper;

    @Override
    public PageResult<AiDailyDigestVo> queryPageList(AiDailyDigestBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiDailyDigest> lqw = Wrappers.lambdaQuery();
        lqw.ge(bo.getBeginDate() != null, AiDailyDigest::getDigestDate, bo.getBeginDate());
        lqw.le(bo.getEndDate() != null, AiDailyDigest::getDigestDate, bo.getEndDate());
        lqw.eq(StringUtils.isNotBlank(bo.getLevel()), AiDailyDigest::getLevel, bo.getLevel());
        lqw.orderByDesc(AiDailyDigest::getDigestDate);
        Page<AiDailyDigestVo> result = dailyDigestMapper.selectVoPage(pageQuery.build(), lqw);
        fillTaskRunId(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AiDailyDigestVo queryByDate(LocalDate digestDate) {
        AiDailyDigestVo vo = dailyDigestMapper.selectVoOne(Wrappers.<AiDailyDigest>lambdaQuery()
            .eq(AiDailyDigest::getDigestDate, digestDate));
        if (vo == null) {
            throw new ServiceException("该日期的运行日报不存在：" + digestDate);
        }
        fillTaskRunId(List.of(vo));
        return vo;
    }

    /**
     * 按 {@code session_id} 回填「来源任务执行」：日报写入期拿不到执行记录（其 {@code session_id}
     * 是执行结束后才回填的），读取期该列已就绪，故在此关联；非任务链路（如对话触发）为空。
     *
     * @param rows 日报行（就地回填）
     */
    private void fillTaskRunId(List<AiDailyDigestVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> sessionIds = rows.stream().map(AiDailyDigestVo::getSessionId)
            .filter(Objects::nonNull).distinct().toList();
        if (sessionIds.isEmpty()) {
            return;
        }
        Map<Long, Long> runBySession = new HashMap<>();
        for (AiAgentTaskRun run : taskRunMapper.selectList(Wrappers.<AiAgentTaskRun>lambdaQuery()
            .in(AiAgentTaskRun::getSessionId, sessionIds))) {
            runBySession.merge(run.getSessionId(), run.getId(), Math::max);
        }
        for (AiDailyDigestVo row : rows) {
            Long runId = runBySession.get(row.getSessionId());
            if (runId != null) {
                row.setTaskRunId(runId);
            }
        }
    }

}
