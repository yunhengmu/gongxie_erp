package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.dromara.ai.domain.AiUsageStat;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * AI 用量统计 Mapper
 *
 * @author BizBuddy
 */
public interface AiUsageStatMapper extends BaseMapperPlus<AiUsageStat, AiUsageStat> {

    /**
     * 按「日期 + 智能体 + 模型 + 用户 + 用量类型」累加写入（PostgreSQL upsert）。
     *
     * @param stat 本次增量（id 由调用方生成；model_id 不可为空，否则唯一索引无法命中冲突）
     * @return 影响行数
     */
    @Insert("""
        insert into ai_usage_stat (id, stat_date, agent_id, model_id, user_id, usage_type, prompt_tokens,
                                   completion_tokens, total_tokens, request_count, tool_call_count, cost,
                                   create_time, update_time)
        values (#{stat.id}, #{stat.statDate}, #{stat.agentId}, #{stat.modelId}, #{stat.userId}, #{stat.usageType},
                #{stat.promptTokens}, #{stat.completionTokens}, #{stat.totalTokens}, #{stat.requestCount},
                #{stat.toolCallCount}, #{stat.cost}, now(), now())
        on conflict (stat_date, agent_id, model_id, user_id, usage_type) do update set
            prompt_tokens = ai_usage_stat.prompt_tokens + excluded.prompt_tokens,
            completion_tokens = ai_usage_stat.completion_tokens + excluded.completion_tokens,
            total_tokens = ai_usage_stat.total_tokens + excluded.total_tokens,
            request_count = ai_usage_stat.request_count + excluded.request_count,
            tool_call_count = ai_usage_stat.tool_call_count + excluded.tool_call_count,
            cost = ai_usage_stat.cost + excluded.cost,
            update_time = now()
        """)
    int upsert(@Param("stat") AiUsageStat stat);

    /**
     * 按「日期 + 智能体 + 模型 + 用户 + 用量类型」累加写入（MySQL upsert）。
     *
     * <p>与 {@link #upsert} **语义等价**：MySQL 8.0 不支持 PostgreSQL 的
     * {@code on conflict … excluded.}，改用 {@code on duplicate key update}，
     * 本次增量取自 {@code values(列)}；依赖唯一键 {@code uk_ai_usage} 命中冲突。</p>
     *
     * @param stat 本次增量（id 由调用方生成；model_id 不可为空，否则唯一索引无法命中冲突）
     * @return 影响行数
     */
    @Insert("""
        insert into ai_usage_stat (id, stat_date, agent_id, model_id, user_id, usage_type, prompt_tokens,
                                   completion_tokens, total_tokens, request_count, tool_call_count, cost,
                                   create_time, update_time)
        values (#{stat.id}, #{stat.statDate}, #{stat.agentId}, #{stat.modelId}, #{stat.userId}, #{stat.usageType},
                #{stat.promptTokens}, #{stat.completionTokens}, #{stat.totalTokens}, #{stat.requestCount},
                #{stat.toolCallCount}, #{stat.cost}, now(), now())
        on duplicate key update
            prompt_tokens = prompt_tokens + values(prompt_tokens),
            completion_tokens = completion_tokens + values(completion_tokens),
            total_tokens = total_tokens + values(total_tokens),
            request_count = request_count + values(request_count),
            tool_call_count = tool_call_count + values(tool_call_count),
            cost = cost + values(cost),
            update_time = now()
        """)
    int upsertMysql(@Param("stat") AiUsageStat stat);

}