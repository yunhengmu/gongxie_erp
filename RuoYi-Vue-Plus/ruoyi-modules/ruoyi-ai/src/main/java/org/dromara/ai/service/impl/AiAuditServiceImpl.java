package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiRouteExpertCall;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiMessageAuditBo;
import org.dromara.ai.domain.bo.AiToolCallAuditBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageAuditExportVo;
import org.dromara.ai.domain.vo.AiMessageAuditVo;
import org.dromara.ai.domain.vo.AiToolCallAuditVo;
import org.dromara.ai.domain.vo.AiToolCallStatVo;
import org.dromara.ai.mapper.AiMessageMapper;
import org.dromara.ai.mapper.AiRouteExpertCallMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiAuditService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AI 会话与工具调用审计 Service 业务层处理
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiAuditServiceImpl implements IAiAuditService {

    /**
     * 消息角色：用户
     */
    private static final String ROLE_USER = "user";

    /**
     * 消息角色：助手
     */
    private static final String ROLE_ASSISTANT = "assistant";

    /**
     * 工具调用成功状态
     */
    private static final String STATE_SUCCESS = "SUCCESS";

    /**
     * 导出时间格式
     */
    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiMessageMapper messageMapper;

    private final AiToolCallLogMapper toolCallLogMapper;

    private final AiSessionMapper sessionMapper;

    private final IAiAgentService agentService;

    /**
     * 专家调用明细（REQ-AI-30）：智能体筛选项需覆盖「编排中出现过的专家」
     */
    private final AiRouteExpertCallMapper expertCallMapper;

    private final UserService userService;

    @Override
    public PageResult<AiMessageAuditVo> queryMessagePage(AiMessageAuditBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiMessage> lqw = buildMessageWrapper(bo);
        if (lqw == null) {
            // 会话维度条件未命中任何会话，直接返回空页
            return PageResult.build(List.of(), 0);
        }
        Page<AiMessage> page = messageMapper.selectPage(pageQuery.build(), lqw);
        List<AiMessageAuditVo> rows = toMessageVos(page.getRecords());
        return PageResult.build(rows, page.getTotal());
    }

    @Override
    public List<AiMessageAuditExportVo> buildMessageExport(AiMessageAuditBo bo) {
        LambdaQueryWrapper<AiMessage> lqw = buildMessageWrapper(bo);
        if (lqw == null) {
            return List.of();
        }
        List<AiMessageAuditVo> rows = toMessageVos(messageMapper.selectList(lqw));
        List<AiMessageAuditExportVo> result = new ArrayList<>(rows.size());
        for (AiMessageAuditVo row : rows) {
            AiMessageAuditExportVo vo = new AiMessageAuditExportVo();
            vo.setSessionTitle(row.getSessionTitle());
            vo.setAgentName(row.getAgentName());
            vo.setUserName(row.getUserName());
            vo.setRoleText(roleText(row.getRole()));
            vo.setContent(row.getContent());
            vo.setStatusText("1".equals(row.getStatus()) ? "失败" : "正常");
            vo.setErrorMsg(row.getErrorMsg());
            vo.setToolCallCount(row.getToolCallCount());
            vo.setTotalTokens(row.getTotalTokens());
            vo.setDurationMs(row.getDurationMs());
            vo.setCreateTime(row.getCreateTime() == null ? "" : row.getCreateTime().format(EXPORT_TIME_FORMAT));
            result.add(vo);
        }
        return result;
    }

    @Override
    public PageResult<AiToolCallAuditVo> queryToolCallPage(AiToolCallAuditBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiToolCallLog> lqw = buildToolCallWrapper(bo);
        if (lqw == null) {
            return PageResult.build(List.of(), 0);
        }
        Page<AiToolCallLog> page = toolCallLogMapper.selectPage(pageQuery.build(), lqw);
        return PageResult.build(toToolCallVos(page.getRecords()), page.getTotal());
    }

    @Override
    public List<AiToolCallStatVo> queryToolCallStats(AiToolCallAuditBo bo) {
        LambdaQueryWrapper<AiToolCallLog> lqw = buildToolCallWrapper(bo);
        if (lqw == null) {
            return List.of();
        }
        List<AiToolCallLog> list = toolCallLogMapper.selectList(lqw);
        // 明细量级受筛选条件（含时间范围）约束，此处按工具在内存中聚合；若后续数据量增大可下沉为 SQL 聚合
        Map<String, AiToolCallStatVo> stats = new LinkedHashMap<>();
        Map<String, Long> durations = new HashMap<>();
        for (AiToolCallLog log : list) {
            String toolName = StringUtils.isBlank(log.getToolName()) ? "unknown" : log.getToolName();
            AiToolCallStatVo stat = stats.computeIfAbsent(toolName, name -> {
                AiToolCallStatVo vo = new AiToolCallStatVo();
                vo.setToolName(name);
                return vo;
            });
            stat.setTotalCount(stat.getTotalCount() + 1);
            if (STATE_SUCCESS.equals(log.getState())) {
                stat.setSuccessCount(stat.getSuccessCount() + 1);
            }
            durations.merge(toolName, log.getDurationMs() == null ? 0L : log.getDurationMs(), Long::sum);
        }
        List<AiToolCallStatVo> result = new ArrayList<>(stats.size());
        stats.forEach((toolName, stat) -> {
            stat.finish(durations.getOrDefault(toolName, 0L));
            result.add(stat);
        });
        return result;
    }

    @Override
    public List<AiAgentVo> queryReferencedAgentOptions() {
        Set<Long> agentIds = new LinkedHashSet<>();
        // ① 工具调用明细的归属智能体（含专家；agent_id 为空的早期历史行不产生选项）
        LambdaQueryWrapper<AiToolCallLog> callLqw = Wrappers.lambdaQuery();
        callLqw.select(AiToolCallLog::getAgentId);
        callLqw.isNotNull(AiToolCallLog::getAgentId);
        callLqw.groupBy(AiToolCallLog::getAgentId);
        for (AiToolCallLog log : toolCallLogMapper.selectList(callLqw)) {
            if (log.getAgentId() != null) {
                agentIds.add(log.getAgentId());
            }
        }
        // ② 编排中被调用过的专家（专家不是会话智能体，故不会被任何会话维度选项覆盖）
        LambdaQueryWrapper<AiRouteExpertCall> expertLqw = Wrappers.lambdaQuery();
        expertLqw.select(AiRouteExpertCall::getExpertAgentId);
        expertLqw.isNotNull(AiRouteExpertCall::getExpertAgentId);
        expertLqw.groupBy(AiRouteExpertCall::getExpertAgentId);
        for (AiRouteExpertCall call : expertCallMapper.selectList(expertLqw)) {
            if (call.getExpertAgentId() != null) {
                agentIds.add(call.getExpertAgentId());
            }
        }
        if (agentIds.isEmpty()) {
            return List.of();
        }
        // 按主键直取（含已停用智能体：历史数据仍需可筛可显示），并按主键升序稳定排序
        List<AiAgentVo> options = new ArrayList<>(agentService.queryEntityVos(agentIds));
        options.sort(Comparator.comparing(AiAgentVo::getId, Comparator.nullsLast(Long::compareTo)));
        return options;
    }

    /**
     * 构建消息查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器；会话维度条件未命中任何会话时返回 null
     */
    private LambdaQueryWrapper<AiMessage> buildMessageWrapper(AiMessageAuditBo bo) {
        LambdaQueryWrapper<AiMessage> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getKeyword()), AiMessage::getContent, bo.getKeyword());
        lqw.eq(StringUtils.isNotBlank(bo.getRole()), AiMessage::getRole, bo.getRole());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiMessage::getStatus, bo.getStatus());
        lqw.eq(bo.getSessionId() != null, AiMessage::getSessionId, bo.getSessionId());
        lqw.ge(bo.getBeginTime() != null, AiMessage::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiMessage::getCreateTime, bo.getEndTime());
        if (needSessionFilter(bo.getSessionTitle(), bo.getAgentId(), bo.getUserId())) {
            List<Long> sessionIds = querySessionIds(bo.getSessionTitle(), bo.getAgentId(), bo.getUserId());
            if (sessionIds.isEmpty()) {
                return null;
            }
            lqw.in(AiMessage::getSessionId, sessionIds);
        }
        lqw.orderByDesc(AiMessage::getId);
        return lqw;
    }

    /**
     * 构建工具调用查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器；会话维度条件未命中任何会话时返回 null
     */
    private LambdaQueryWrapper<AiToolCallLog> buildToolCallWrapper(AiToolCallAuditBo bo) {
        LambdaQueryWrapper<AiToolCallLog> lqw = Wrappers.lambdaQuery();
        if (StringUtils.isNotBlank(bo.getKeyword())) {
            String keyword = bo.getKeyword();
            lqw.and(w -> w.like(AiToolCallLog::getToolName, keyword)
                .or().like(AiToolCallLog::getToolInput, keyword)
                .or().like(AiToolCallLog::getToolOutput, keyword));
        }
        lqw.eq(StringUtils.isNotBlank(bo.getToolName()), AiToolCallLog::getToolName, bo.getToolName());
        lqw.eq(StringUtils.isNotBlank(bo.getState()), AiToolCallLog::getState, bo.getState());
        lqw.eq(bo.getSessionId() != null, AiToolCallLog::getSessionId, bo.getSessionId());
        // 归属智能体（REQ-AI-30 Phase 2）：直查明细行自身的 agent_id，专家执行链路可据此筛出专家的工具调用
        lqw.eq(bo.getAgentId() != null, AiToolCallLog::getAgentId, bo.getAgentId());
        lqw.eq(bo.getUserId() != null, AiToolCallLog::getUserId, bo.getUserId());
        lqw.ge(bo.getBeginTime() != null, AiToolCallLog::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiToolCallLog::getCreateTime, bo.getEndTime());
        if (bo.getOwnerUserId() != null) {
            List<Long> sessionIds = querySessionIds(null, null, bo.getOwnerUserId());
            if (sessionIds.isEmpty()) {
                return null;
            }
            lqw.in(AiToolCallLog::getSessionId, sessionIds);
        }
        lqw.orderByDesc(AiToolCallLog::getId);
        return lqw;
    }

    /**
     * 是否需要按会话维度过滤。
     *
     * @param sessionTitle 会话标题
     * @param agentId      智能体主键
     * @param userId       归属用户
     * @return 是否需要过滤
     */
    private boolean needSessionFilter(String sessionTitle, Long agentId, Long userId) {
        return StringUtils.isNotBlank(sessionTitle) || agentId != null || userId != null;
    }

    /**
     * 按会话维度条件查询会话主键。
     *
     * @param sessionTitle 会话标题
     * @param agentId      智能体主键
     * @param userId       归属用户
     * @return 会话主键列表
     */
    private List<Long> querySessionIds(String sessionTitle, Long agentId, Long userId) {
        LambdaQueryWrapper<AiSession> lqw = Wrappers.lambdaQuery();
        lqw.select(AiSession::getId);
        lqw.like(StringUtils.isNotBlank(sessionTitle), AiSession::getSessionTitle, sessionTitle);
        lqw.eq(agentId != null, AiSession::getAgentId, agentId);
        lqw.eq(userId != null, AiSession::getUserId, userId);
        return sessionMapper.selectList(lqw).stream().map(AiSession::getId).toList();
    }

    /**
     * 消息实体批量转换为审计视图对象，并补齐会话上下文。
     *
     * @param list 消息实体
     * @return 审计视图对象
     */
    private List<AiMessageAuditVo> toMessageVos(List<AiMessage> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // 消息审计的智能体固定为会话智能体，故不需要额外的明细行归属智能体
        SessionContext context = loadSessionContext(list.stream().map(AiMessage::getSessionId).toList(), List.of());
        List<AiMessageAuditVo> rows = new ArrayList<>(list.size());
        for (AiMessage message : list) {
            AiMessageAuditVo vo = new AiMessageAuditVo();
            BeanUtils.copyProperties(message, vo);
            context.fill(vo, message.getSessionId());
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 工具调用实体批量转换为审计视图对象，并补齐会话上下文。
     *
     * @param list 工具调用实体
     * @return 审计视图对象
     */
    private List<AiToolCallAuditVo> toToolCallVos(List<AiToolCallLog> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        // 智能体名称需覆盖**明细行自身的 agent_id**（专家执行链路写入专家主键，可能不是会话智能体）
        List<Long> agentIds = list.stream().map(AiToolCallLog::getAgentId)
            .filter(Objects::nonNull).toList();
        SessionContext context = loadSessionContext(list.stream().map(AiToolCallLog::getSessionId).toList(),
            agentIds);
        List<AiToolCallAuditVo> rows = new ArrayList<>(list.size());
        for (AiToolCallLog log : list) {
            AiToolCallAuditVo vo = new AiToolCallAuditVo();
            BeanUtils.copyProperties(log, vo);
            context.fill(vo, log.getSessionId());
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 加载会话上下文（会话标题 / 智能体名称 / 归属用户昵称）。
     *
     * @param sessionIds    会话主键集合
     * @param extraAgentIds 额外需要回填名称的智能体主键（工具调用明细行自身的 {@code agent_id}）
     * @return 会话上下文
     */
    private SessionContext loadSessionContext(List<Long> sessionIds, List<Long> extraAgentIds) {
        Set<Long> distinct = new HashSet<>(sessionIds);
        Map<Long, AiSession> sessions = new HashMap<>();
        for (AiSession session : sessionMapper.selectByIds(distinct)) {
            sessions.put(session.getId(), session);
        }
        Set<Long> agentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        if (extraAgentIds != null) {
            extraAgentIds.stream().filter(Objects::nonNull).forEach(agentIds::add);
        }
        sessions.values().forEach(session -> {
            if (session.getAgentId() != null) {
                agentIds.add(session.getAgentId());
            }
            if (session.getUserId() != null) {
                userIds.add(session.getUserId());
            }
        });
        Map<Long, String> agentNames = new HashMap<>();
        if (!agentIds.isEmpty()) {
            for (AiAgent agent : agentService.queryEntities(agentIds)) {
                agentNames.put(agent.getId(), agent.getAgentName());
            }
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        return new SessionContext(sessions, agentNames, userNames);
    }

    /**
     * 消息角色文案。
     *
     * @param role 角色
     * @return 文案
     */
    private String roleText(String role) {
        if (ROLE_USER.equals(role)) {
            return "用户";
        }
        if (ROLE_ASSISTANT.equals(role)) {
            return "助手";
        }
        return StringUtils.isBlank(role) ? "系统" : role;
    }

    /**
     * 会话上下文（标题 / 智能体 / 归属用户）。
     *
     * @param sessions   会话映射
     * @param agentNames 智能体名称映射
     * @param userNames  用户昵称映射
     */
    private record SessionContext(Map<Long, AiSession> sessions,
                                  Map<Long, String> agentNames,
                                  Map<Long, String> userNames) {

        /**
         * 把会话上下文写入消息审计视图对象。
         *
         * @param vo        视图对象
         * @param sessionId 会话主键
         */
        private void fill(AiMessageAuditVo vo, Long sessionId) {
            AiSession session = sessions.get(sessionId);
            if (session == null) {
                return;
            }
            vo.setSessionTitle(session.getSessionTitle());
            vo.setAgentId(session.getAgentId());
            vo.setAgentName(agentNames.get(session.getAgentId()));
            vo.setUserId(session.getUserId());
            vo.setUserName(userNames.get(session.getUserId()));
        }

        /**
         * 把会话上下文写入工具调用审计视图对象。
         *
         * @param vo        视图对象
         * @param sessionId 会话主键
         */
        private void fill(AiToolCallAuditVo vo, Long sessionId) {
            AiSession session = sessions.get(sessionId);
            // 归属智能体优先取明细行自身的 agent_id（专家执行链路＝专家），为空回落会话智能体
            // （Phase 2 之前的历史行无该列值）。REQ-AI-30 Phase 2 第三项
            Long agentId = vo.getAgentId() != null ? vo.getAgentId()
                : (session == null ? null : session.getAgentId());
            vo.setAgentId(agentId);
            vo.setAgentName(agentId == null ? null : agentNames.get(agentId));
            if (session == null) {
                return;
            }
            vo.setSessionTitle(session.getSessionTitle());
            if (vo.getUserId() == null) {
                vo.setUserId(session.getUserId());
            }
            // 注意：userId 为空时不可直接用不可变 Map 取值（Map.of 对 null 键抛 NPE）
            vo.setUserName(vo.getUserId() == null ? null : userNames.get(vo.getUserId()));
        }
    }

}
