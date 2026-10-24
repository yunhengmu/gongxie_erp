package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiPermissionDecisionLog;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiPermissionDecisionBo;
import org.dromara.ai.domain.vo.AiPermissionDecisionVo;
import org.dromara.ai.mapper.AiPermissionDecisionLogMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiPermissionDecisionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具权限决策日志 Service 业务层处理
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiPermissionDecisionServiceImpl implements IAiPermissionDecisionService {

    private final AiPermissionDecisionLogMapper decisionLogMapper;

    private final AiSessionMapper sessionMapper;

    private final IAiAgentService agentService;

    private final UserService userService;

    @Override
    public void recordAsk(Long sessionId, Long messageId, Long agentId, Long userId,
                          String toolCode, String toolName, String toolInput) {
        write(sessionId, messageId, agentId, userId, toolCode, toolName,
            AiPermissionDecisionLog.DECISION_ASK, toolInput, null, "命中「需人工确认」策略，已暂停等待用户处置");
    }

    @Override
    public void recordAllow(Long sessionId, Long messageId, Long agentId, Long userId,
                            String toolCode, String toolName, String toolInput, Long durationMs) {
        write(sessionId, messageId, agentId, userId, toolCode, toolName,
            AiPermissionDecisionLog.DECISION_ALLOW, toolInput, durationMs, "命中「允许」策略，已直接执行");
    }

    @Override
    public void applyConfirm(Long messageId, boolean confirmed, boolean remember, String reason) {
        if (messageId == null) {
            return;
        }
        LambdaQueryWrapper<AiPermissionDecisionLog> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiPermissionDecisionLog::getMessageId, messageId);
        lqw.eq(AiPermissionDecisionLog::getDecision, AiPermissionDecisionLog.DECISION_ASK);
        AiPermissionDecisionLog update = new AiPermissionDecisionLog();
        update.setConfirmed(confirmed ? AiPermissionDecisionLog.CONFIRM_APPROVED : AiPermissionDecisionLog.CONFIRM_REJECTED);
        update.setConfirmSource(remember
            ? AiPermissionDecisionLog.CONFIRM_REMEMBER : AiPermissionDecisionLog.CONFIRM_ONCE);
        if (!confirmed && StringUtils.isNotBlank(reason)) {
            // 用户自定义拒绝原因留痕：解释「为什么没执行」，与 confirmed=0 互补
            update.setReason("用户拒绝：" + reason);
        }
        decisionLogMapper.update(update, lqw);
    }

    @Override
    public PageResult<AiPermissionDecisionVo> queryPage(AiPermissionDecisionBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiPermissionDecisionLog> lqw = buildQueryWrapper(bo);
        Page<AiPermissionDecisionLog> page = decisionLogMapper.selectPage(pageQuery.build(), lqw);
        return PageResult.build(toVos(page.getRecords()), page.getTotal());
    }

    /**
     * 写入一条决策日志（失败不影响主流程）。
     *
     * @param sessionId  会话主键
     * @param messageId  助手消息主键
     * @param agentId    智能体主键
     * @param userId     触发用户
     * @param toolCode   工具编码
     * @param toolName   工具名称
     * @param decision   决策
     * @param toolInput  工具入参 JSON
     * @param durationMs 工具执行耗时（毫秒）
     * @param reason     决策说明
     */
    private void write(Long sessionId, Long messageId, Long agentId, Long userId, String toolCode, String toolName,
                       String decision, String toolInput, Long durationMs, String reason) {
        try {
            AiPermissionDecisionLog entity = new AiPermissionDecisionLog();
            entity.setSessionId(sessionId);
            entity.setMessageId(messageId);
            entity.setAgentId(agentId);
            entity.setUserId(userId);
            entity.setToolCode(toolCode);
            entity.setToolName(toolName);
            entity.setDecision(decision);
            entity.setDecisionSource(AiPermissionDecisionLog.SOURCE_AGENT_TOOL);
            entity.setMatchedRule(toolCode);
            entity.setReason(reason);
            entity.setToolInput(toolInput);
            entity.setDurationMs(durationMs);
            decisionLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计写入失败不打断对话主流程，但必须留下服务端日志
            log.error("权限决策日志写入失败：sessionId={}, messageId={}, toolCode={}, decision={}",
                sessionId, messageId, toolCode, decision, e);
        }
    }

    /**
     * 构建决策日志查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiPermissionDecisionLog> buildQueryWrapper(AiPermissionDecisionBo bo) {
        LambdaQueryWrapper<AiPermissionDecisionLog> lqw = Wrappers.lambdaQuery();
        lqw.eq(bo.getSessionId() != null, AiPermissionDecisionLog::getSessionId, bo.getSessionId());
        lqw.like(StringUtils.isNotBlank(bo.getToolCode()), AiPermissionDecisionLog::getToolCode, bo.getToolCode());
        lqw.eq(StringUtils.isNotBlank(bo.getDecision()), AiPermissionDecisionLog::getDecision, bo.getDecision());
        lqw.eq(StringUtils.isNotBlank(bo.getConfirmed()), AiPermissionDecisionLog::getConfirmed, bo.getConfirmed());
        lqw.eq(bo.getUserId() != null, AiPermissionDecisionLog::getUserId, bo.getUserId());
        lqw.eq(bo.getAgentId() != null, AiPermissionDecisionLog::getAgentId, bo.getAgentId());
        lqw.ge(bo.getBeginTime() != null, AiPermissionDecisionLog::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiPermissionDecisionLog::getCreateTime, bo.getEndTime());
        lqw.orderByDesc(AiPermissionDecisionLog::getId);
        return lqw;
    }

    /**
     * 批量转换并补齐会话标题 / 智能体名称 / 用户昵称。
     *
     * @param list 决策日志实体
     * @return 视图对象
     */
    private List<AiPermissionDecisionVo> toVos(List<AiPermissionDecisionLog> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> sessionIds = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (AiPermissionDecisionLog entity : list) {
            if (entity.getSessionId() != null) {
                sessionIds.add(entity.getSessionId());
            }
            if (entity.getAgentId() != null) {
                agentIds.add(entity.getAgentId());
            }
            if (entity.getUserId() != null) {
                userIds.add(entity.getUserId());
            }
        }
        Map<Long, String> sessionTitles = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            for (AiSession session : sessionMapper.selectByIds(sessionIds)) {
                sessionTitles.put(session.getId(), session.getSessionTitle());
            }
        }
        Map<Long, String> agentNames = new HashMap<>();
        if (!agentIds.isEmpty()) {
            for (AiAgent agent : agentService.queryEntities(agentIds)) {
                agentNames.put(agent.getId(), agent.getAgentName());
            }
        }
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        List<AiPermissionDecisionVo> rows = new ArrayList<>(list.size());
        for (AiPermissionDecisionLog entity : list) {
            AiPermissionDecisionVo vo = new AiPermissionDecisionVo();
            BeanUtils.copyProperties(entity, vo);
            vo.setSessionTitle(sessionTitles.get(entity.getSessionId()));
            vo.setAgentName(agentNames.get(entity.getAgentId()));
            vo.setUserName(userNames.get(entity.getUserId()));
            rows.add(vo);
        }
        return rows;
    }

}