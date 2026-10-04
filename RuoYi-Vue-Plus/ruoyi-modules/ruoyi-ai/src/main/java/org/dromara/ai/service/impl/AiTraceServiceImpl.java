package org.dromara.ai.service.impl;

import cn.hutool.core.lang.Dict;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.bo.AiTraceBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.vo.AiTraceVo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.mapper.AiTraceMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiTraceService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 链路追踪 Service 业务层处理（REQ-AI-28）
 *
 * <p>同一次提问的两段生成（HITL 暂停 + 恢复）共用一条链路：暂停时回填待确认消息主键，
 * 恢复段按该消息主键找回同一条并累加耗时与用量，因此「状态迁移」呈现为一条完整时间线。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiTraceServiceImpl implements IAiTraceService {

    /**
     * 状态迁移条数上限（异常链路保护）
     */
    private static final int MAX_TRANSITIONS = 100;

    /**
     * 时间格式（时间线展示用，精确到毫秒）
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AiTraceMapper baseMapper;

    private final AiSessionMapper sessionMapper;

    private final UserService userService;

    private final IAiAgentService agentService;

    @Override
    public PageResult<AiTraceVo> queryPageList(AiTraceBo bo, PageQuery pageQuery) {
        Page<AiTraceVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiTraceVo> records = result.getRecords();
        fillDisplayFields(records, false);
        return PageResult.build(records, result.getTotal());
    }

    @Override
    public AiTraceVo queryById(Long id) {
        AiTraceVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("链路不存在或已被删除");
        }
        fillDisplayFields(List.of(vo), true);
        return vo;
    }

    @Override
    public Long start(Long sessionId, Long agentId, Long userId, String source, AgentSnapshot snapshot,
                      Long parentTraceId) {
        AiTrace entity = new AiTrace();
        entity.setSessionId(sessionId);
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setSource(StringUtils.isBlank(source) ? AiTrace.SOURCE_CHAT : source);
        entity.setParentTraceId(parentTraceId);
        entity.setStatus(AiTrace.STATUS_RUNNING);
        entity.setDurationMs(0L);
        entity.setToolCallCount(0);
        entity.setPromptTokens(0);
        entity.setCompletionTokens(0);
        entity.setTotalTokens(0);
        if (snapshot != null) {
            entity.setModelId(snapshot.modelId());
            entity.setModelCode(snapshot.modelCode());
            entity.setModelProvider(snapshot.modelProvider());
            entity.setSummaryModelCode(snapshot.summaryModelCode());
            entity.setMemoryModelCode(snapshot.memoryModelCode());
            entity.setSubagentModelCode(snapshot.subagentModelCode());
            entity.setContextJson(snapshot.context() == null
                ? null : JsonUtils.toJsonString(snapshot.context()));
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setTransitionsJson(JsonUtils.toJsonString(
            List.of(transition("start", parentTraceId == null ? "来源 " + entity.getSource()
                : "子链路（专家调用）"))));
        baseMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Long findIdByMessage(Long sessionId, Long messageId) {
        if (sessionId == null || messageId == null) {
            return null;
        }
        LambdaQueryWrapper<AiTrace> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTrace::getSessionId, sessionId);
        lqw.eq(AiTrace::getMessageId, messageId);
        lqw.orderByDesc(AiTrace::getId);
        lqw.last("limit 1");
        AiTrace latest = baseMapper.selectOne(lqw);
        return latest == null ? null : latest.getId();
    }

    @Override
    public void appendTransition(Long traceId, String state, String detail) {
        if (traceId == null || StringUtils.isBlank(state)) {
            return;
        }
        AiTrace entity = baseMapper.selectById(traceId);
        if (entity == null) {
            return;
        }
        List<Map<String, Object>> transitions = parseTransitions(entity.getTransitionsJson());
        if (transitions.size() >= MAX_TRANSITIONS) {
            return;
        }
        transitions.add(transition(state, detail));
        AiTrace update = new AiTrace();
        update.setId(traceId);
        update.setTransitionsJson(JsonUtils.toJsonString(transitions));
        update.setUpdateTime(LocalDateTime.now());
        try {
            baseMapper.updateById(update);
        } catch (Exception e) {
            // 链路留痕失败不得影响对话主流程
            log.warn("追加链路状态迁移失败：traceId={}, state={}", traceId, state, e);
        }
    }

    @Override
    public void attachMessage(Long traceId, Long messageId) {
        if (traceId == null || messageId == null) {
            return;
        }
        AiTrace update = new AiTrace();
        update.setId(traceId);
        update.setMessageId(messageId);
        update.setUpdateTime(LocalDateTime.now());
        baseMapper.updateById(update);
    }

    @Override
    public void accumulate(Long traceId, String status, Long durationMs, Integer toolCallCount,
                           Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorMsg) {
        if (traceId == null) {
            return;
        }
        AiTrace exists = baseMapper.selectById(traceId);
        if (exists == null) {
            return;
        }
        AiTrace update = new AiTrace();
        update.setId(traceId);
        update.setStatus(StringUtils.isBlank(status) ? exists.getStatus() : status);
        update.setDurationMs(add(exists.getDurationMs(), durationMs));
        update.setToolCallCount(add(exists.getToolCallCount(), toolCallCount));
        update.setPromptTokens(add(exists.getPromptTokens(), promptTokens));
        update.setCompletionTokens(add(exists.getCompletionTokens(), completionTokens));
        update.setTotalTokens(add(exists.getTotalTokens(), totalTokens));
        if (StringUtils.isNotBlank(errorMsg)) {
            update.setErrorMsg(abbreviate(errorMsg, 1000));
        }
        update.setUpdateTime(LocalDateTime.now());
        try {
            baseMapper.updateById(update);
        } catch (Exception e) {
            log.warn("更新链路收尾信息失败：traceId={}", traceId, e);
        }
    }

    /**
     * 构造一条状态迁移节点。
     *
     * @param state  状态节点
     * @param detail 明细
     * @return 迁移节点
     */
    private Map<String, Object> transition(String state, String detail) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("at", LocalDateTime.now().format(TIME_FORMATTER));
        node.put("state", state);
        if (StringUtils.isNotBlank(detail)) {
            node.put("detail", detail);
        }
        return node;
    }

    /**
     * 累加数值（原值与增量均为空时返回 null，否则按 0 处理）。
     *
     * @param base   原值
     * @param delta  增量
     * @return 累加结果
     */
    private Long add(Long base, Long delta) {
        if (base == null && delta == null) {
            return null;
        }
        return (base == null ? 0L : base) + (delta == null ? 0L : delta);
    }

    /**
     * 累加数值（整型重载）。
     *
     * @param base  原值
     * @param delta 增量
     * @return 累加结果
     */
    private Integer add(Integer base, Integer delta) {
        if (base == null && delta == null) {
            return null;
        }
        return (base == null ? 0 : base) + (delta == null ? 0 : delta);
    }

    /**
     * 截断超长文本。
     *
     * @param text      原文
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    /**
     * 解析状态迁移 JSON。
     *
     * @param json JSON 数组文本
     * @return 迁移节点列表（非法或为空时返回空列表）
     */
    private List<Map<String, Object>> parseTransitions(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            List<Dict> nodes = JsonUtils.parseArrayMap(json);
            return nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
        } catch (Exception e) {
            log.warn("链路状态迁移解析失败：{}", json);
            return new ArrayList<>();
        }
    }

    /**
     * 构建链路动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiTrace> buildQueryWrapper(AiTraceBo bo) {
        LambdaQueryWrapper<AiTrace> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getSessionId() != null, AiTrace::getSessionId, bo.getSessionId());
        lqw.eq(bo.getMessageId() != null, AiTrace::getMessageId, bo.getMessageId());
        lqw.eq(bo.getAgentId() != null, AiTrace::getAgentId, bo.getAgentId());
        lqw.eq(StringUtils.isNotBlank(bo.getSource()), AiTrace::getSource, bo.getSource());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiTrace::getStatus, bo.getStatus());
        lqw.like(StringUtils.isNotBlank(bo.getModelCode()), AiTrace::getModelCode, bo.getModelCode());
        lqw.ge(bo.getBeginTime() != null, AiTrace::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiTrace::getCreateTime, bo.getEndTime());
        lqw.orderByDesc(AiTrace::getCreateTime);
        return lqw;
    }

    /**
     * 填充智能体、会话、用户显示字段；详情时解析上下文清单与状态迁移。
     *
     * @param list      链路列表
     * @param withDetail 是否解析上下文与时间线（列表页不解析，减少开销）
     */
    private void fillDisplayFields(List<AiTraceVo> list, boolean withDetail) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> agentIds = new HashSet<>();
        Set<Long> sessionIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AiTraceVo vo : list) {
            if (vo.getAgentId() != null) {
                agentIds.add(vo.getAgentId());
            }
            if (vo.getSessionId() != null) {
                sessionIds.add(vo.getSessionId());
            }
            if (vo.getUserId() != null) {
                userIds.add(vo.getUserId());
            }
            if (withDetail) {
                vo.setContext(parseContext(vo.getContextJson()));
                vo.setTransitions(parseTransitions(vo.getTransitionsJson()));
            }
        }
        Map<Long, AiAgent> agents = new HashMap<>();
        if (!agentIds.isEmpty()) {
            for (AiAgent agent : agentService.queryEntities(agentIds)) {
                agents.put(agent.getId(), agent);
            }
        }
        Map<Long, String> sessionTitles = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            for (AiSession session : sessionMapper.selectByIds(sessionIds)) {
                sessionTitles.put(session.getId(), session.getSessionTitle());
            }
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        for (AiTraceVo vo : list) {
            AiAgent agent = vo.getAgentId() == null ? null : agents.get(vo.getAgentId());
            vo.setAgentName(agent == null ? null : agent.getAgentName());
            vo.setAgentCode(agent == null ? null : agent.getAgentCode());
            vo.setSessionTitle(vo.getSessionId() == null ? null : sessionTitles.get(vo.getSessionId()));
            // 注意：不可变 Map（Map.of）对 null 键会抛 NPE，取值前必须先判空
            vo.setUserName(vo.getUserId() == null ? null : userNames.get(vo.getUserId()));
        }
    }

    /**
     * 解析上下文清单 JSON。
     *
     * @param json JSON 对象文本
     * @return 上下文清单（非法或为空时返回空 Map）
     */
    private Map<String, Object> parseContext(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> context = JsonUtils.parseMap(json);
            return context == null ? Map.of() : context;
        } catch (Exception e) {
            log.warn("链路上下文清单解析失败：{}", json);
            return Map.of();
        }
    }

}