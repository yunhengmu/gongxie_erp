package org.dromara.ai.service;

import org.dromara.ai.domain.AiAgent;

/**
 * AI 配额校验 Service 接口（REQ-AI-07）
 *
 * @author BizBuddy
 */
public interface IAiQuotaService {

    /**
     * 校验当前用户在当前智能体上的配额，超限时抛出业务异常。
     *
     * <p>口径：Redis 当日 / 当月计数优先（准实时闸门），计数缺失时回退数据库当日聚合。</p>
     *
     * @param userId     用户主键
     * @param definition 智能体定义（配额为 0 表示不限）
     */
    void check(Long userId, AiAgent definition);

}