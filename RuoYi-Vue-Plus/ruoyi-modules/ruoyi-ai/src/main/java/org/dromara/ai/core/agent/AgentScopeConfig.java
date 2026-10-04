package org.dromara.ai.core.agent;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * AgentScope 装配所需的基础 Bean。
 *
 * @author BizBuddy
 */
@Configuration
public class AgentScopeConfig {

    /**
     * AgentScope 状态键前缀；经 Redisson NameMapper 后实际落库为 {@code bizbuddy:ai:agentscope:...}
     */
    public static final String STATE_KEY_PREFIX = "bizbuddy:ai:agentscope";

    /**
     * 技能仓库使用的 schema 名（本项目技能表在 public）
     */
    private static final String SKILL_SCHEMA = "public";

    /**
     * 技能表名（列结构对框架保持兼容，见 script/sql/postgres/postgres_ry_ai.sql 第十六部分）
     */
    private static final String SKILL_TABLE = "ai_skill";

    /**
     * 技能参考资料表名
     */
    private static final String SKILL_RESOURCE_TABLE = "ai_skill_resource";

    /**
     * 复用项目已有 RedissonClient 构建 AgentScope 状态存储（承载多轮对话记忆）。
     *
     * <p>使用 {@link RedisAgentStateStore} 而非 {@code RedissonAgentStateStore}：后者在 AgentScope 2.0.3
     * 已标注 {@code @Deprecated}，前者是官方推荐入口（{@code RedissonClientAdapter} 的示例即指向它）。
     * 两者键布局完全一致（默认前缀 {@code agentscope:session:}、会话键集后缀 {@code :_keys}），
     * 故本次切换**不改变已有数据的键结构，无需数据迁移**（2.0.3 源码实证）。</p>
     *
     * @param redissonClient 项目 Redisson 客户端
     * @return 状态存储 Bean
     */
    @Bean
    public AgentStateStore aiAgentStateStore(RedissonClient redissonClient) {
        return RedisAgentStateStore.builder()
            .redissonClient(redissonClient)
            .keyPrefix(STATE_KEY_PREFIX)
            .build();
    }

    /**
     * 技能仓库（REQ-AI-10）：技能的持久化由 AgentScope 的 PostgreSQL 技能仓库实现提供，
     * 但**表名与 schema 定制为本项目的 {@code public.ai_skill} / {@code public.ai_skill_resource}**，
     * 且 {@code createIfNotExist=false} + {@code writeable=false} ——
     * 建表与写入统一由本项目的 SQL 脚本与 Service 负责，从而保留审计列与生效范围治理列。
     *
     * @param dataSource 项目数据源
     * @return 技能仓库 Bean（只读）
     */
    @Bean
    public AgentSkillRepository aiSkillRepository(DataSource dataSource) {
        return PostgresSkillRepository.builder(dataSource)
            .schemaName(SKILL_SCHEMA)
            .skillsTableName(SKILL_TABLE)
            .resourcesTableName(SKILL_RESOURCE_TABLE)
            .createIfNotExist(false)
            .writeable(false)
            .build();
    }

}
