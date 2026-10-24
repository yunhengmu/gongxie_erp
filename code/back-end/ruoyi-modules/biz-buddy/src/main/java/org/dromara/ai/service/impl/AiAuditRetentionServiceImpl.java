package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;
import org.dromara.ai.mapper.AiAuditRetentionMapper;
import org.dromara.ai.service.IAiAuditRetentionService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 审计与运行数据保留策略 Service 业务层处理。
 *
 * <p>保留天数取运行期配置（Redis，默认 0＝不限）；清理为人工触发，逐表按
 * {@code create_time < 截止时间} **物理删除**并回带逐表行数。</p>
 *
 * <p><b>口径</b>：① 0＝不清理（默认，行为不变）；② 只清运行 / 审计明细 6 表，不含会话正文
 * {@code ai_message}；③ 逐表 DELETE 各自原子、不加跨表事务（避免长事务持锁）；④ 父表与子表
 * （{@code ai_route_run} 与 {@code ai_route_expert_call}）各自按时间清理，同一保留天数下基本对齐，
 * 极端边界（父行早于截止、子行晚于截止）可能出现孤儿明细，审计场景不做级联。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiAuditRetentionServiceImpl implements IAiAuditRetentionService {

    /**
     * 截止时间展示格式
     */
    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiRuntimeConfigStore runtimeConfigStore;

    private final AiAuditRetentionMapper retentionMapper;

    @Override
    public AiAuditRetentionVo getRetention() {
        return buildVo(runtimeConfigStore.auditRetention(), null);
    }

    @Override
    public void saveRetention(AiAuditRetentionBo bo) {
        runtimeConfigStore.updateAuditRetention(bo);
    }

    @Override
    public AiAuditRetentionVo purge() {
        AiAuditRetentionBo bo = runtimeConfigStore.auditRetention();
        int days = bo.getRetentionDays();
        if (days <= 0) {
            throw new ServiceException("保留天数为 0（不限），未执行清理；请先设置保留天数并保存");
        }
        LocalDateTime cutoff = cutoffOf(days);
        Map<String, Long> purged = new LinkedHashMap<>();
        long total = 0L;
        for (TableOps ops : tables()) {
            int deleted = ops.purge().apply(cutoff);
            purged.put(ops.label(), (long) deleted);
            total += deleted;
        }
        log.info("审计数据清理完成：cutoff={}, total={}, detail={}", cutoff, total, purged);
        return buildVo(bo, purged);
    }

    /**
     * 按保留天数计算截止时间（早于该时间的行会被清理）。
     *
     * @param days 保留天数
     * @return 截止时间
     */
    private LocalDateTime cutoffOf(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    /**
     * 构造视图：回带保留天数、截止时间、各表超期行数，以及（可选）本次删除行数。
     *
     * @param bo     当前保留策略
     * @param purged 本次删除行数（查询时为 null）
     * @return 视图对象
     */
    private AiAuditRetentionVo buildVo(AiAuditRetentionBo bo, Map<String, Long> purged) {
        AiAuditRetentionVo vo = new AiAuditRetentionVo();
        vo.setRetentionDays(bo.getRetentionDays());
        Map<String, Long> pending = new LinkedHashMap<>();
        if (bo.getRetentionDays() > 0) {
            LocalDateTime cutoff = cutoffOf(bo.getRetentionDays());
            vo.setCutoff(cutoff.format(CUTOFF_FORMAT));
            for (TableOps ops : tables()) {
                pending.put(ops.label(), ops.count().apply(cutoff));
            }
        }
        vo.setPending(pending);
        vo.setPurged(purged);
        return vo;
    }

    /**
     * 清理任务清单（顺序即页面展示顺序）。
     *
     * @return 逐表计数 / 删除任务
     */
    private List<TableOps> tables() {
        return List.of(
            new TableOps("ai_trace", retentionMapper::countTrace, retentionMapper::purgeTrace),
            new TableOps("ai_tool_call_log", retentionMapper::countToolCallLog, retentionMapper::purgeToolCallLog),
            new TableOps("ai_permission_decision_log", retentionMapper::countPermissionDecisionLog,
                retentionMapper::purgePermissionDecisionLog),
            new TableOps("ai_agent_task_run", retentionMapper::countAgentTaskRun, retentionMapper::purgeAgentTaskRun),
            new TableOps("ai_route_run", retentionMapper::countRouteRun, retentionMapper::purgeRouteRun),
            new TableOps("ai_route_expert_call", retentionMapper::countRouteExpertCall,
                retentionMapper::purgeRouteExpertCall));
    }

    /**
     * 单表清理任务（表名 + 计数 + 物理删除）。
     *
     * @param label 表名（同时作为视图里的键）
     * @param count 计数函数
     * @param purge 物理删除函数
     */
    private record TableOps(String label, Function<LocalDateTime, Long> count,
                            Function<LocalDateTime, Integer> purge) {
    }

}
