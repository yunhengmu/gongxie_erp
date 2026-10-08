package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 编排链路落库（REQ-AI-30）。
 *
 * <p>一次编排：{@code ai_route_run} 一行（自答也落一行），每次专家调用：{@code ai_route_expert_call} 一行
 * （含 {@code raw_output} 原文，是来源标注与追溯的载体）。</p>
 *
 * <p><b>为什么不走 {@code IAiAgentService} 那类带数据权限的服务</b>：编排链路运行在 Reactor 线程，
 * 没有登录上下文，RuoYi 的数据权限处理器在该线程会空指针；故这里直接用无 {@code @DataPermission} 的
 * Mapper。同理审计列由**入参显式落库**——{@code InjectionMetaObjectHandler} 在这种线程取不到登录用户。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRouteRecorder {

    /**
     * {@code ai_route_run.degrade_reason} 的列宽（varchar 500）。
     *
     * <p>专家调用失败时会把上游报文原样带进降级原因（如模型侧 403 报文，动辄数百字符），
     * 超长会让**整条收尾 UPDATE** 失败——不仅降级原因丢失，本次的告警（{@code warning_text}）
     * 与状态也会一并留在 {@code running}，故统一截断后再落库。</p>
     */
    private static final int DEGRADE_REASON_MAX = 500;

    private final AiRouteRunMapper routeRunMapper;

    private final AiRouteExpertCallMapper expertCallMapper;

    /**
     * 开始一次编排（入口智能体的一轮对话即一次编排，无论最终自答还是路由）。
     *
     * @param sessionId    会话主键
     * @param entryAgentId 入口智能体主键
     * @param userId       提问用户
     * @return 编排主键
     */
    public Long startRun(Long sessionId, Long entryAgentId, Long userId) {
        AiRouteRun run = new AiRouteRun();
        run.setSessionId(sessionId);
        run.setEntryAgentId(entryAgentId);
        run.setUserId(userId);
        run.setStatus(AiRouteRun.STATUS_RUNNING);
        run.setExpertCallCount(0);
        run.setCreateBy(userId);
        run.setCreateTime(LocalDateTime.now());
        routeRunMapper.insert(run);
        return run.getId();
    }

    /**
     * 收尾一次编排。
     *
     * <p><b>专家直答修订（2026-10-03）</b>：{@code warning_text} 停写（脱敏 / 事实抽查 / 宽松放行三类
     * 告警来源均已移除，列保留兼容存量数据）。</p>
     *
     * @param runId           编排主键
     * @param route           编排运行状态（提供 action / 决策原文 / 任务片段 / 调用次数）
     * @param status          最终状态（success / degraded / aborted）
     * @param degradeReason   降级或中止原因（可为空）
     * @param durationMs      总耗时（毫秒）
     */
    public void finishRun(Long runId, AiRouteContext route, String status, String degradeReason, long durationMs) {
        if (runId == null) {
            return;
        }
        try {
            routeRunMapper.update(null, Wrappers.<AiRouteRun>lambdaUpdate()
                .eq(AiRouteRun::getId, runId)
                .set(AiRouteRun::getMessageId, route.messageId)
                // action / 决策原文 / 任务片段只在首段赋值；恢复段是新建的上下文（这三项为空），
                // 若无条件更新会把首段的值覆盖成 self / NULL，故改为「非空才更新」
                .set(StringUtils.isNotBlank(route.action), AiRouteRun::getAction, route.action)
                .set(StringUtils.isNotBlank(route.decisionJson), AiRouteRun::getDecisionJson, route.decisionJson)
                .set(StringUtils.isNotBlank(route.taskContext), AiRouteRun::getTaskContext, route.taskContext)
                .set(AiRouteRun::getExpertCallCount, route.expertCallCount.get())
                .set(AiRouteRun::getStatus, status)
                .set(AiRouteRun::getDegradeReason, truncateReason(degradeReason))
                .set(AiRouteRun::getDurationMs, durationMs)
                .set(AiRouteRun::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("编排运行收尾落库失败，runId={}", runId, e);
        }
    }

    /**
     * 截断降级原因到列宽以内（截断后缀本身也计入上限，否则仍会超出列宽）。
     *
     * @param degradeReason 降级或中止原因（可空）
     * @return 落库用文本；为空时返回 null
     */
    private static String truncateReason(String degradeReason) {
        if (StringUtils.isBlank(degradeReason)) {
            return null;
        }
        String suffix = "…（已截断）";
        return degradeReason.length() > DEGRADE_REASON_MAX
            ? degradeReason.substring(0, DEGRADE_REASON_MAX - suffix.length()) + suffix
            : degradeReason;
    }

    /**
     * 落一次专家调用明细（追加型，物理删除）。
     *
     * @param call 专家调用明细
     */
    public void recordExpertCall(AiRouteExpertCall call) {
        try {
            if (call.getCreateTime() == null) {
                call.setCreateTime(LocalDateTime.now());
            }
            expertCallMapper.insert(call);
        } catch (Exception e) {
            log.error("专家调用明细落库失败，routeRunId={}, expertAgentId={}",
                call.getRouteRunId(), call.getExpertAgentId(), e);
        }
    }
}
