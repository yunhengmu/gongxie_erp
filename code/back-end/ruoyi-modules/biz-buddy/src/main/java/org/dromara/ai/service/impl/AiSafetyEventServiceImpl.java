package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.AiSafetyEvent;
import org.dromara.ai.domain.bo.AiSafetyEventBo;
import org.dromara.ai.domain.vo.AiSafetyEventVo;
import org.dromara.ai.mapper.AiSafetyEventMapper;
import org.dromara.ai.service.IAiSafetyEventService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.springframework.stereotype.Service;

/**
 * AI 安全事件 Service 实现
 *
 * <p>追加型明细，留痕失败只告警不抛出（不能因留痕失败影响主流程）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiSafetyEventServiceImpl implements IAiSafetyEventService {

    /**
     * 触发文本摘要最大长度（与 ai_safety_event.text_excerpt 列宽一致）
     */
    private static final int EXCERPT_MAX_LENGTH = 200;

    private final AiSafetyEventMapper baseMapper;

    @Override
    public void record(SafetyScene scene, SafetyDecision decision, String engine,
                       Long sessionId, Long messageId, Long agentId, Long userId, String excerpt) {
        if (scene == null || decision == null) {
            return;
        }
        try {
            AiSafetyEvent entity = new AiSafetyEvent();
            // 走平台统一主键生成器（容忍时钟回拨），不用 IdWorker 的独立单例
            entity.setId(IdGeneratorUtil.nextLongId());
            entity.setScene(scene.code());
            entity.setAction(decision.action().code());
            entity.setEngine(engine);
            entity.setSessionId(sessionId);
            entity.setMessageId(messageId);
            entity.setAgentId(agentId);
            entity.setUserId(userId);
            entity.setHitCount(decision.hits() == null ? 0 : decision.hits().size());
            entity.setHitsJson(JsonUtils.toJsonString(decision.hits()));
            entity.setTextExcerpt(truncate(excerpt));
            baseMapper.insert(entity);
        } catch (Exception e) {
            // 留痕失败不影响主流程
            log.warn("安全事件留痕失败：scene={}, engine={}, userId={}", scene.code(), engine, userId, e);
        }
    }

    @Override
    public PageResult<AiSafetyEventVo> queryPage(AiSafetyEventBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiSafetyEvent> lqw = Wrappers.lambdaQuery();
        lqw.eq(StringUtils.isNotBlank(bo.getScene()), AiSafetyEvent::getScene, bo.getScene());
        lqw.eq(StringUtils.isNotBlank(bo.getAction()), AiSafetyEvent::getAction, bo.getAction());
        lqw.eq(bo.getUserId() != null, AiSafetyEvent::getUserId, bo.getUserId());
        lqw.ge(bo.getBeginTime() != null, AiSafetyEvent::getCreateTime, bo.getBeginTime());
        lqw.le(bo.getEndTime() != null, AiSafetyEvent::getCreateTime, bo.getEndTime());
        lqw.orderByDesc(AiSafetyEvent::getCreateTime);
        Page<AiSafetyEventVo> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AiSafetyEventVo queryById(Long id) {
        AiSafetyEventVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("安全事件不存在或已被删除");
        }
        return vo;
    }

    /**
     * 文本摘要截断（超长时截断至 {@value #EXCERPT_MAX_LENGTH} 字符）。
     *
     * @param text 原文（可空）
     * @return 截断后的文本；原文为空时返回 null
     */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= EXCERPT_MAX_LENGTH ? text : text.substring(0, EXCERPT_MAX_LENGTH);
    }

}