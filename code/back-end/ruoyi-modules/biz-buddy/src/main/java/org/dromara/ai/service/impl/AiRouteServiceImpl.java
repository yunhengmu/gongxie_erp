package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiRouteRun;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteExpertCallVo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiRouteRunMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiRouteService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.ai.util.AiModelCost;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 编排运行查询 Service 实现（REQ-AI-30）。
 *
 * <p><b>读取侧共三处消费</b>：① 任务工作台 / 任务管理按会话查；② 管理面「编排运行」列表（分页 + 筛选）；
 * ③ 管理面统计与详情。三处共用「专家调用明细按 {@code route_run_id} 批量分组 + 专家名回填」与
 * 「显示字段（会话标题 / 入口智能体 / 提问用户）批量回填」两套逻辑，避免逐行查询。</p>
 *
 * <p><b>统计口径与取舍</b>：统计按与列表**同源的筛选条件**取数后**在内存中聚合**
 * （与 {@code AiAuditServiceImpl#queryToolCallStats} 同款范式），但只 select 聚合所需的列
 * （action / status / expert_call_count / duration_ms / message_id），不拉 decision_json 与 task_context
 * 两个大文本。管理面默认「近 7 天」时间范围进一步收敛数据量；数据量显著增大时应下沉为 SQL 聚合
 * （届时再补 {@code ai_route_run.create_time} 索引）。</p>
 *
 * @author BizBuddy
 */
@Service
@RequiredArgsConstructor
public class AiRouteServiceImpl implements IAiRouteService {

    private final AiRouteRunMapper routeRunMapper;

    private final AiRouteExpertCallMapper expertCallMapper;

    private final AiSessionMapper sessionMapper;

    private final AiMessageMapper messageMapper;

    private final AiModelConfigMapper modelConfigMapper;

    private final IAiSessionService sessionService;

    private final IAiAgentService agentService;

    private final UserService userService;

    @Override
    public List<AiRouteRunDetailVo> querySessionRuns(Long sessionId) {
        // 会话归属校验：只能看自己的会话编排（与对话工作台其他接口同口径）
        sessionService.getOwnedSession(sessionId);
        LambdaQueryWrapper<AiRouteRun> runQuery = Wrappers.lambdaQuery();
        runQuery.eq(AiRouteRun::getSessionId, sessionId);
        runQuery.orderByDesc(AiRouteRun::getId);
        List<AiRouteRun> runs = routeRunMapper.selectList(runQuery);
        if (runs.isEmpty()) {
            return List.of();
        }
        List<Long> runIds = runs.stream().map(AiRouteRun::getId).toList();
        Map<Long, List<AiRouteExpertCallVo>> grouped = loadExpertCalls(runIds);
        // 与本会话相关的助手消息 Token（入口轮 + 整合轮口径，见 AiRouteRunDetailVo）
        Map<Long, AiMessage> messages = loadMessages(runs.stream().map(AiRouteRun::getMessageId).toList());
        // 费用折算：入口智能体 + 各专家智能体一次批量装配，并按各智能体模型单价折算
        Map<Long, AiAgent> agents = loadRunAgents(runs, grouped.values());
        Map<Long, AiModelConfig> prices = loadModelConfigs(agents.values());
        List<AiRouteRunDetailVo> result = new ArrayList<>(runs.size());
        for (AiRouteRun run : runs) {
            AiRouteRunDetailVo detail = toDetail(run, grouped.getOrDefault(run.getId(), List.of()));
            AiMessage message = messages.get(run.getMessageId());
            fillRunTokens(detail, message);
            fillRunCosts(detail, message, agents.get(run.getEntryAgentId()), agents, prices);
            result.add(detail);
        }
        return result;
    }

    @Override
    public PageResult<AiRouteRunVo> queryPageList(AiRouteRunBo bo, PageQuery pageQuery) {
        Page<AiRouteRunVo> page = routeRunMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiRouteRunVo> records = page.getRecords();
        fillRunDisplayFields(records);
        return PageResult.build(records, page.getTotal());
    }

    @Override
    public AiRouteRunDetailVo queryById(Long id) {
        AiRouteRun run = routeRunMapper.selectById(id);
        if (run == null) {
            throw new ServiceException("编排运行不存在或已被删除");
        }
        List<AiRouteExpertCallVo> calls = loadExpertCalls(List.of(id)).getOrDefault(id, List.of());
        AiRouteRunDetailVo detail = toDetail(run, calls);
        // message_id 可能为空（HITL 暂停 / 执行中：编排尚未收尾），故不能直接 List.of(...)（会拒 null）
        AiMessage message = run.getMessageId() == null ? null : messageMapper.selectById(run.getMessageId());
        fillRunTokens(detail, message);
        // 费用折算：入口智能体 + 各专家智能体一次批量装配
        Set<Long> agentIds = new LinkedHashSet<>();
        if (run.getEntryAgentId() != null) {
            agentIds.add(run.getEntryAgentId());
        }
        calls.forEach(call -> {
            if (call.getExpertAgentId() != null) {
                agentIds.add(call.getExpertAgentId());
            }
        });
        Map<Long, AiAgent> agents = loadAgents(agentIds);
        Map<Long, AiModelConfig> prices = loadModelConfigs(agents.values());
        fillRunCosts(detail, message, agents.get(run.getEntryAgentId()), agents, prices);
        AiAgent entry = agents.get(run.getEntryAgentId());
        detail.setEntryAgentName(entry == null ? null : entry.getAgentName());
        AiSession session = run.getSessionId() == null ? null : sessionMapper.selectById(run.getSessionId());
        detail.setSessionTitle(session == null ? null : session.getSessionTitle());
        Map<Long, String> userNames = run.getUserId() == null ? Map.of()
            : userService.selectUserNicksByIds(Set.of(run.getUserId()));
        detail.setUserName(run.getUserId() == null ? null : userNames.get(run.getUserId()));
        return detail;
    }

    @Override
    public AiRouteRunStatsVo queryStats(AiRouteRunBo bo) {
        LambdaQueryWrapper<AiRouteRun> lqw = buildQueryWrapper(bo);
        // 列裁剪：只取聚合所需列，避开 decision_json / task_context 两个大文本
        lqw.select(AiRouteRun::getId, AiRouteRun::getMessageId, AiRouteRun::getEntryAgentId, AiRouteRun::getAction,
            AiRouteRun::getStatus, AiRouteRun::getExpertCallCount, AiRouteRun::getDurationMs);
        List<AiRouteRun> runs = routeRunMapper.selectList(lqw);
        AiRouteRunStatsVo vo = new AiRouteRunStatsVo();
        long self = 0;
        long route = 0;
        long pinned = 0;
        long running = 0;
        long success = 0;
        long degraded = 0;
        long aborted = 0;
        long pending = 0;
        long expertCalls = 0;
        long duration = 0;
        for (AiRouteRun run : runs) {
            if (AiRouteRun.ACTION_SELF.equals(run.getAction())) {
                self++;
            } else if (AiRouteRun.ACTION_ROUTE.equals(run.getAction())) {
                route++;
            } else if (AiRouteRun.ACTION_PINNED.equals(run.getAction())) {
                // 用户指定专家（REQ-AI-31）：与 route 同属「路由类」，另单列
                pinned++;
            }
            String status = run.getStatus();
            if (AiRouteRun.STATUS_RUNNING.equals(status)) {
                running++;
            } else if (AiRouteRun.STATUS_SUCCESS.equals(status)) {
                success++;
            } else if (AiRouteRun.STATUS_DEGRADED.equals(status)) {
                degraded++;
            } else if (AiRouteRun.STATUS_ABORTED.equals(status)) {
                aborted++;
            } else if (AiRouteRun.STATUS_PENDING.equals(status)) {
                pending++;
            }
            expertCalls += run.getExpertCallCount() == null ? 0 : run.getExpertCallCount();
            duration += run.getDurationMs() == null ? 0 : run.getDurationMs();
        }
        long total = runs.size();
        vo.setTotalCount(total);
        vo.setSelfCount(self);
        // 「路由类」＝自动路由 + 用户指定（REQ-AI-31：pinned 与 route 同属路由类）
        vo.setRouteCount(route + pinned);
        vo.setPinnedCount(pinned);
        vo.setRunningCount(running);
        vo.setSuccessCount(success);
        vo.setDegradedCount(degraded);
        vo.setAbortedCount(aborted);
        vo.setPendingCount(pending);
        // 路由命中率：action 在决策后即落库，故分母为全部编排（含未终结）；
        // 分子含用户指定（REQ-AI-31）
        vo.setRouteHitRate(rate(route + pinned, total));
        // 降级率：running / pending 未终结，不计入分母（否则 HITL 暂停会拉低降级率）
        vo.setDegradeRate(rate(degraded, success + degraded + aborted));
        vo.setExpertCallTotal(expertCalls);
        vo.setTotalDurationMs(duration);
        vo.setAvgDurationMs(total == 0 ? null : duration / total);
        // Token 汇总：专家轮取自 ai_route_expert_call，入口轮取自 ai_message（含整合轮）
        List<Long> runIds = runs.stream().map(AiRouteRun::getId).toList();
        ExpertTokens expert = loadExpertTokens(runIds);
        Map<Long, AiMessage> messages = loadMessages(runs.stream().map(AiRouteRun::getMessageId).toList());
        long entryTokens = 0;
        for (AiMessage message : messages.values()) {
            entryTokens += message.getTotalTokens() == null ? 0 : message.getTotalTokens();
        }
        vo.setExpertPromptTokens(expert.prompt());
        vo.setExpertCompletionTokens(expert.completion());
        vo.setEntryTokens(entryTokens);
        vo.setTotalTokens(expert.prompt() + expert.completion() + entryTokens);
        // 费用汇总：入口轮（助手消息 × 入口智能体模型单价）+ 专家轮（专家调用 × 各专家模型单价）
        Set<Long> agentIds = new LinkedHashSet<>();
        runs.forEach(run -> {
            if (run.getEntryAgentId() != null) {
                agentIds.add(run.getEntryAgentId());
            }
        });
        agentIds.addAll(expert.tokensByAgent().keySet());
        Map<Long, AiAgent> agents = loadAgents(agentIds);
        Map<Long, AiModelConfig> prices = loadModelConfigs(agents.values());
        BigDecimal entryCostTotal = BigDecimal.ZERO;
        for (AiRouteRun run : runs) {
            AiMessage message = messages.get(run.getMessageId());
            entryCostTotal = entryCostTotal.add(agentCost(agents.get(run.getEntryAgentId()),
                message == null ? null : longOf(message.getPromptTokens()),
                message == null ? null : longOf(message.getCompletionTokens()), prices));
        }
        BigDecimal expertCostTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, long[]> entry : expert.tokensByAgent().entrySet()) {
            long[] tokens = entry.getValue();
            expertCostTotal = expertCostTotal.add(agentCost(agents.get(entry.getKey()), tokens[0], tokens[1], prices));
        }
        vo.setEntryCost(entryCostTotal);
        vo.setExpertCost(expertCostTotal);
        vo.setTotalCost(entryCostTotal.add(expertCostTotal));
        return vo;
    }

    /**
     * 构建编排运行动态查询条件（列表与统计**同源**，保证统计口径与所见列表一致）。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiRouteRun> buildQueryWrapper(AiRouteRunBo bo) {
        LambdaQueryWrapper<AiRouteRun> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getSessionId() != null, AiRouteRun::getSessionId, bo.getSessionId());
        lqw.eq(bo.getMessageId() != null, AiRouteRun::getMessageId, bo.getMessageId());
        lqw.eq(bo.getEntryAgentId() != null, AiRouteRun::getEntryAgentId, bo.getEntryAgentId());
        lqw.eq(bo.getUserId() != null, AiRouteRun::getUserId, bo.getUserId());
        lqw.eq(StringUtils.isNotBlank(bo.getAction()), AiRouteRun::getAction, bo.getAction());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiRouteRun::getStatus, bo.getStatus());
        lqw.ge(bo.getBeginTime() != null, AiRouteRun::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiRouteRun::getCreateTime, bo.getEndTime());
        if (bo.getExpertAgentId() != null) {
            // 存在子查询（而非 in 子句）：走 idx_ai_route_call_expert，且「无匹配」自然为空集
            lqw.exists("select 1 from ai_route_expert_call c where c.route_run_id = ai_route_run.id"
                + " and c.expert_agent_id = {0}", bo.getExpertAgentId());
        }
        lqw.orderByDesc(AiRouteRun::getCreateTime);
        lqw.orderByDesc(AiRouteRun::getId);
        return lqw;
    }

    /**
     * 批量回填列表行的显示字段与 Token 汇总。
     *
     * @param records 编排运行列表（原地回填）
     */
    private void fillRunDisplayFields(List<AiRouteRunVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> agentIds = new LinkedHashSet<>();
        Set<Long> sessionIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        List<Long> runIds = new ArrayList<>(records.size());
        for (AiRouteRunVo vo : records) {
            if (vo.getEntryAgentId() != null) {
                agentIds.add(vo.getEntryAgentId());
            }
            if (vo.getSessionId() != null) {
                sessionIds.add(vo.getSessionId());
            }
            if (vo.getUserId() != null) {
                userIds.add(vo.getUserId());
            }
            runIds.add(vo.getId());
        }
        Set<Long> allAgentIds = new LinkedHashSet<>(agentIds);
        Map<Long, String> sessionTitles = new HashMap<>();
        for (AiSession session : sessionMapper.selectByIds(sessionIds)) {
            sessionTitles.put(session.getId(), session.getSessionTitle());
        }
        // 注意：Map.of / 不可变 Map 对 null 键会抛 NPE，取值前必须先判空
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        Map<Long, List<AiRouteExpertCallVo>> calls = loadExpertCalls(runIds);
        // 费用折算：把专家智能体一并纳入装配，并按各智能体模型单价折算
        calls.values().forEach(list -> list.forEach(call -> {
            if (call.getExpertAgentId() != null) {
                allAgentIds.add(call.getExpertAgentId());
            }
        }));
        Map<Long, AiAgent> agents = loadAgents(allAgentIds);
        Map<Long, AiModelConfig> prices = loadModelConfigs(agents.values());
        Map<Long, AiMessage> messages = loadMessages(records.stream().map(AiRouteRunVo::getMessageId).toList());
        Map<Long, Long> entryTokens = new HashMap<>();
        // ai_message 的 Token 列为 Integer，视图对象的 Token 列为 Long，此处显式转换
        messages.forEach((id, message) -> entryTokens.put(id,
            message.getTotalTokens() == null ? null : message.getTotalTokens().longValue()));
        for (AiRouteRunVo vo : records) {
            AiAgent agent = vo.getEntryAgentId() == null ? null : agents.get(vo.getEntryAgentId());
            vo.setEntryAgentName(agent == null ? null : agent.getAgentName());
            vo.setEntryAgentCode(agent == null ? null : agent.getAgentCode());
            vo.setSessionTitle(vo.getSessionId() == null ? null : sessionTitles.get(vo.getSessionId()));
            vo.setUserName(vo.getUserId() == null ? null : userNames.get(vo.getUserId()));
            List<AiRouteExpertCallVo> runCalls = calls.getOrDefault(vo.getId(), List.of());
            vo.setExpertAgentNames(joinExpertNames(runCalls));
            Long entry = vo.getMessageId() == null ? null : entryTokens.get(vo.getMessageId());
            Long expert = tokensOf(runCalls);
            vo.setEntryTokens(entry);
            vo.setExpertTokens(expert);
            vo.setTotalTokens(sum(entry, expert));
            // 费用：入口轮按入口智能体模型单价、专家轮按各专家模型单价逐条折算
            AiMessage message = vo.getMessageId() == null ? null : messages.get(vo.getMessageId());
            BigDecimal entryCost = agentCost(agent, message == null ? null : longOf(message.getPromptTokens()),
                message == null ? null : longOf(message.getCompletionTokens()), prices);
            BigDecimal expertCost = BigDecimal.ZERO;
            boolean priced = agentPriced(agent, prices);
            for (AiRouteExpertCallVo call : runCalls) {
                AiAgent expertAgent = agents.get(call.getExpertAgentId());
                BigDecimal callCost = agentCost(expertAgent, call.getPromptTokens(), call.getCompletionTokens(), prices);
                call.setCost(callCost);
                expertCost = expertCost.add(callCost);
                priced = priced && agentPriced(expertAgent, prices);
            }
            vo.setEntryCost(entryCost);
            vo.setExpertCost(expertCost);
            vo.setTotalCost(entryCost.add(expertCost));
            vo.setPriceConfigured(priced);
        }
    }

    /**
     * 按编排主键集合批量取专家调用明细，并按编排分组（含专家名回填）。
     *
     * @param runIds 编排主键集合
     * @return 编排主键 → 专家调用明细（按尝试顺序）
     */
    private Map<Long, List<AiRouteExpertCallVo>> loadExpertCalls(List<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiRouteExpertCall> callQuery = Wrappers.lambdaQuery();
        callQuery.in(AiRouteExpertCall::getRouteRunId, runIds);
        callQuery.orderByAsc(AiRouteExpertCall::getRouteRunId);
        callQuery.orderByAsc(AiRouteExpertCall::getAttemptNo);
        callQuery.orderByAsc(AiRouteExpertCall::getId);
        List<AiRouteExpertCallVo> calls = expertCallMapper.selectVoList(callQuery);
        if (calls.isEmpty()) {
            return Map.of();
        }
        // 专家名称 / 编码由 ai_agent 批量回填（历史专家卡片要用它渲染；专家可能已停用，故按主键直取）
        Set<Long> expertIds = new LinkedHashSet<>();
        calls.forEach(call -> {
            if (call.getExpertAgentId() != null) {
                expertIds.add(call.getExpertAgentId());
            }
        });
        Map<Long, AiAgent> experts = loadAgents(expertIds);
        Map<Long, List<AiRouteExpertCallVo>> grouped = new LinkedHashMap<>();
        for (AiRouteExpertCallVo call : calls) {
            AiAgent expert = experts.get(call.getExpertAgentId());
            if (expert != null) {
                call.setExpertAgentName(expert.getAgentName());
                call.setExpertAgentKey(expert.getAgentCode());
            }
            grouped.computeIfAbsent(call.getRouteRunId(), key -> new ArrayList<>()).add(call);
        }
        return grouped;
    }

    /**
     * 按主键批量取智能体（含已停用）。
     *
     * @param agentIds 智能体主键集合
     * @return 主键 → 智能体
     */
    private Map<Long, AiAgent> loadAgents(Set<Long> agentIds) {
        Map<Long, AiAgent> agents = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return agents;
        }
        for (AiAgent agent : agentService.queryEntities(agentIds)) {
            agents.put(agent.getId(), agent);
        }
        return agents;
    }

    /**
     * 按消息主键批量取助手消息（用于取入口轮 Token；逻辑删除的消息取不到，按空值回落）。
     *
     * @param messageIds 消息主键集合（可含 null）
     * @return 主键 → 消息
     */
    private Map<Long, AiMessage> loadMessages(List<Long> messageIds) {
        Map<Long, AiMessage> messages = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return messages;
        }
        Set<Long> distinct = new LinkedHashSet<>();
        messageIds.stream().filter(Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) {
            return messages;
        }
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.in(AiMessage::getId, distinct);
        // 带上主键（见 loadExpertTokens 的说明：只选可空列时整行为 null）
        lqw.select(AiMessage::getId, AiMessage::getTotalTokens, AiMessage::getPromptTokens,
            AiMessage::getCompletionTokens);
        for (AiMessage message : messageMapper.selectList(lqw)) {
            if (message != null) {
                messages.put(message.getId(), message);
            }
        }
        return messages;
    }

    /**
     * 取一批编排下全部专家调用的 Token 汇总（输入 / 输出分别求和，用于统计卡分列展示），
     * 并按专家智能体分组（供费用折算按各专家模型单价分别计算）。
     *
     * @param runIds 编排主键集合
     * @return 专家轮 Token 汇总
     */
    private ExpertTokens loadExpertTokens(List<Long> runIds) {
        Map<Long, long[]> byAgent = new LinkedHashMap<>();
        if (runIds == null || runIds.isEmpty()) {
            return new ExpertTokens(0L, 0L, byAgent);
        }
        LambdaQueryWrapper<AiRouteExpertCall> lqw = Wrappers.lambdaQuery();
        lqw.in(AiRouteExpertCall::getRouteRunId, runIds);
        // 必须带上主键：暂停中（pending_confirm）的调用两列 Token 均为空，
        // 而「只选可空列」时 MyBatis 会把整行判为 null 放进列表（实测踩坑）
        lqw.select(AiRouteExpertCall::getId, AiRouteExpertCall::getExpertAgentId,
            AiRouteExpertCall::getPromptTokens, AiRouteExpertCall::getCompletionTokens);
        long prompt = 0;
        long completion = 0;
        for (AiRouteExpertCall call : expertCallMapper.selectList(lqw)) {
            if (call == null) {
                continue;
            }
            long callPrompt = call.getPromptTokens() == null ? 0 : call.getPromptTokens();
            long callCompletion = call.getCompletionTokens() == null ? 0 : call.getCompletionTokens();
            prompt += callPrompt;
            completion += callCompletion;
            if (call.getExpertAgentId() != null) {
                long[] acc = byAgent.computeIfAbsent(call.getExpertAgentId(), key -> new long[2]);
                acc[0] += callPrompt;
                acc[1] += callCompletion;
            }
        }
        return new ExpertTokens(prompt, completion, byAgent);
    }

    /**
     * 专家轮 Token 汇总（输入 / 输出）与按专家分组的 Token（[0]=输入，[1]=输出）。
     *
     * @param prompt       输入 Token 合计
     * @param completion   输出 Token 合计
     * @param tokensByAgent 专家智能体主键 → 输入 / 输出 Token
     */
    private record ExpertTokens(long prompt, long completion, Map<Long, long[]> tokensByAgent) {
    }

    /**
     * 取一批编排涉及的全部智能体（入口智能体 + 各专家智能体）。
     *
     * @param runs         编排运行列表
     * @param groupedCalls 编排主键 → 专家调用明细
     * @return 智能体主键 → 智能体
     */
    private Map<Long, AiAgent> loadRunAgents(List<AiRouteRun> runs, Collection<List<AiRouteExpertCallVo>> groupedCalls) {
        Set<Long> agentIds = new LinkedHashSet<>();
        for (AiRouteRun run : runs) {
            if (run.getEntryAgentId() != null) {
                agentIds.add(run.getEntryAgentId());
            }
        }
        if (groupedCalls != null) {
            for (List<AiRouteExpertCallVo> calls : groupedCalls) {
                for (AiRouteExpertCallVo call : calls) {
                    if (call.getExpertAgentId() != null) {
                        agentIds.add(call.getExpertAgentId());
                    }
                }
            }
        }
        return loadAgents(agentIds);
    }

    /**
     * 批量取模型配置（按智能体绑定的模型主键集合）。
     *
     * @param agents 智能体集合
     * @return 模型主键 → 模型配置（用于按单价折算费用）
     */
    private Map<Long, AiModelConfig> loadModelConfigs(Collection<AiAgent> agents) {
        Map<Long, AiModelConfig> configs = new HashMap<>();
        Set<Long> modelIds = new LinkedHashSet<>();
        for (AiAgent agent : agents) {
            if (agent != null && agent.getModelId() != null) {
                modelIds.add(agent.getModelId());
            }
        }
        if (modelIds.isEmpty()) {
            return configs;
        }
        for (AiModelConfig model : modelConfigMapper.selectByIds(modelIds)) {
            if (model != null) {
                configs.put(model.getId(), model);
            }
        }
        return configs;
    }

    /**
     * 按智能体当前绑定的模型单价，把一组 Token 折算为费用（元）。
     *
     * <p>读时折算：路由表未落 modelId，故按**当前**模型单价回溯计算；未配置单价的模型按 0 计。</p>
     *
     * @param agent      智能体（可空）
     * @param prompt     输入 Token（可空）
     * @param completion 输出 Token（可空）
     * @param prices     模型主键 → 模型配置
     * @return 费用（元）
     */
    private BigDecimal agentCost(AiAgent agent, Long prompt, Long completion, Map<Long, AiModelConfig> prices) {
        if (agent == null || agent.getModelId() == null) {
            return BigDecimal.ZERO;
        }
        AiModelConfig model = prices.get(agent.getModelId());
        if (model == null) {
            return BigDecimal.ZERO;
        }
        return AiModelCost.cost(model.getInputPrice(), model.getOutputPrice(),
            prompt == null ? 0L : prompt, completion == null ? 0L : completion);
    }

    /**
     * 智能体绑定的模型是否已配置单价（任一单价非空即视为已配置）。
     *
     * @param agent  智能体（可空）
     * @param prices 模型主键 → 模型配置
     * @return 是否已配置单价
     */
    private boolean agentPriced(AiAgent agent, Map<Long, AiModelConfig> prices) {
        if (agent == null || agent.getModelId() == null) {
            return false;
        }
        AiModelConfig model = prices.get(agent.getModelId());
        return model != null && (model.getInputPrice() != null || model.getOutputPrice() != null);
    }

    /**
     * Integer Token 转 Long（可空）。
     *
     * @param value Token 数
     * @return Long 值或 null
     */
    private Long longOf(Integer value) {
        return value == null ? null : value.longValue();
    }

    /**
     * 详情视图的 Token 汇总（入口轮取自助手消息，专家轮取自专家调用明细）。
     *
     * @param detail  详情视图对象（原地回填）
     * @param message 本轮助手消息（可空：编排尚未终结或消息已删除）
     */
    private void fillRunTokens(AiRouteRunDetailVo detail, AiMessage message) {
        // ai_message 的 Token 列为 Integer，视图对象的 Token 列为 Long，此处显式转换
        Long entry = message == null || message.getTotalTokens() == null ? null : message.getTotalTokens().longValue();
        Long prompt = message == null || message.getPromptTokens() == null
            ? null : message.getPromptTokens().longValue();
        Long completion = message == null || message.getCompletionTokens() == null
            ? null : message.getCompletionTokens().longValue();
        Long expert = tokensOf(detail.getExpertCalls());
        detail.setEntryPromptTokens(prompt);
        detail.setEntryCompletionTokens(completion);
        detail.setEntryTokens(entry);
        detail.setExpertTokens(expert);
        detail.setTotalTokens(sum(entry, expert));
    }

    /**
     * 详情视图的费用折算（入口轮按入口智能体模型单价、专家轮按各专家模型单价；逐条专家调用回填费用）。
     *
     * @param detail     详情视图对象（原地回填）
     * @param message    本轮助手消息（可空）
     * @param entryAgent 入口智能体（可空）
     * @param agents     智能体主键 → 智能体（含各专家）
     * @param prices     模型主键 → 模型配置
     */
    private void fillRunCosts(AiRouteRunDetailVo detail, AiMessage message, AiAgent entryAgent,
                              Map<Long, AiAgent> agents, Map<Long, AiModelConfig> prices) {
        BigDecimal entryCost = agentCost(entryAgent,
            message == null ? null : longOf(message.getPromptTokens()),
            message == null ? null : longOf(message.getCompletionTokens()), prices);
        BigDecimal expertCost = BigDecimal.ZERO;
        boolean priced = agentPriced(entryAgent, prices);
        List<AiRouteExpertCallVo> calls = detail.getExpertCalls();
        if (calls != null) {
            for (AiRouteExpertCallVo call : calls) {
                AiAgent expertAgent = agents.get(call.getExpertAgentId());
                BigDecimal callCost = agentCost(expertAgent, call.getPromptTokens(), call.getCompletionTokens(), prices);
                call.setCost(callCost);
                expertCost = expertCost.add(callCost);
                priced = priced && agentPriced(expertAgent, prices);
            }
        }
        detail.setEntryCost(entryCost);
        detail.setExpertCost(expertCost);
        detail.setTotalCost(entryCost.add(expertCost));
        detail.setPriceConfigured(priced);
    }

    /**
     * 专家调用明细的 Token 之和（输入 + 输出）。
     *
     * @param calls 专家调用明细
     * @return Token 合计（无明细时为 null）
     */
    private Long tokensOf(List<AiRouteExpertCallVo> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        long tokens = 0;
        boolean hasValue = false;
        for (AiRouteExpertCallVo call : calls) {
            if (call.getPromptTokens() != null) {
                tokens += call.getPromptTokens();
                hasValue = true;
            }
            if (call.getCompletionTokens() != null) {
                tokens += call.getCompletionTokens();
                hasValue = true;
            }
        }
        return hasValue ? tokens : null;
    }

    /**
     * 两个可空数值相加（都为 null 时为 null）。
     *
     * @param left  左操作数
     * @param right 右操作数
     * @return 合计或 null
     */
    private Long sum(Long left, Long right) {
        if (left == null && right == null) {
            return null;
        }
        return (left == null ? 0L : left) + (right == null ? 0L : right);
    }

    /**
     * 参与专家名称（去重、顿号连接）。
     *
     * @param calls 专家调用明细
     * @return 形如 {@code 财务分析专家、公告管理员专家}；无名称时为 null
     */
    private String joinExpertNames(List<AiRouteExpertCallVo> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (AiRouteExpertCallVo call : calls) {
            if (StringUtils.isNotBlank(call.getExpertAgentName())) {
                names.add(call.getExpertAgentName());
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 百分比（保留 1 位小数）；分母为 0 时返回 null（避免出现无意义的 0%）。
     *
     * @param numerator   分子
     * @param denominator 分母
     * @return 百分比或 null
     */
    private Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    /**
     * 编排运行实体 → 明细视图对象。
     *
     * @param run   编排运行
     * @param calls 该次编排的专家调用明细
     * @return 明细视图对象
     */
    private AiRouteRunDetailVo toDetail(AiRouteRun run, List<AiRouteExpertCallVo> calls) {
        AiRouteRunDetailVo vo = new AiRouteRunDetailVo();
        vo.setId(run.getId());
        vo.setSessionId(run.getSessionId());
        vo.setMessageId(run.getMessageId());
        vo.setEntryAgentId(run.getEntryAgentId());
        vo.setAction(run.getAction());
        vo.setDecisionJson(run.getDecisionJson());
        vo.setTaskContext(run.getTaskContext());
        vo.setExpertCallCount(run.getExpertCallCount());
        vo.setStatus(run.getStatus());
        vo.setDegradeReason(run.getDegradeReason());
        vo.setWarningText(run.getWarningText());
        vo.setDurationMs(run.getDurationMs());
        vo.setCreateTime(run.getCreateTime());
        vo.setExpertCalls(calls);
        // 结构化决策判定（docs/21 §6）：影子 / 闸门两档都留痕，详情页据此展示判定明细
        vo.setDecisionSource(run.getDecisionSource());
        vo.setJudgeMode(run.getJudgeMode());
        vo.setJudgeChoice(run.getJudgeChoice());
        vo.setJudgeConfidence(run.getJudgeConfidence());
        vo.setJudgeLatencyMs(run.getJudgeLatencyMs());
        vo.setJudgeTokens(run.getJudgeTokens());
        vo.setJudgeError(run.getJudgeError());
        return vo;
    }
}