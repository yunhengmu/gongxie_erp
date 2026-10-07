package org.dromara.ai.core.agent;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

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
     * 技能仓库（REQ-AI-10）：技能的持久化由 AgentScope 官方技能仓库实现提供，
     * 本项目同时支持 PostgreSQL（默认）与 MySQL 8.0：运行时按数据源的数据库产品名二选一装配，
     * 两端都把表名定制为本项目的 {@code ai_skill} / {@code ai_skill_resource}，且
     * {@code createIfNotExist=false} + {@code writeable=false} ——
     * 建表与写入统一由本项目的 SQL 脚本与 Service 负责，从而保留审计列与生效范围治理列。
     *
     * <p>注意两者的「库名」语义不同：PostgreSQL 用 schema（{@code public}），MySQL 用 database
     * （即当前连接的 catalog，形如 {@code biz_buddy}）——后者由连接动态读取，避免把库名写死。</p>
     *
     * @param dataSource 项目数据源
     * @return 技能仓库 Bean（只读）
     */
    @Bean
    public AgentSkillRepository aiSkillRepository(DataSource dataSource) {
        String productName;
        String catalog;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            productName = metaData.getDatabaseProductName();
            // MySQL 的 database 即 JDBC catalog；PostgreSQL 的 catalog 是库名、schema 才是 public
            catalog = connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("无法探测数据源类型以装配技能仓库", e);
        }

        if (productName != null && productName.toLowerCase(Locale.ROOT).contains("mysql")) {
            return MysqlSkillRepository.builder(dataSource)
                .databaseName(catalog)
                .skillsTableName(SKILL_TABLE)
                .resourcesTableName(SKILL_RESOURCE_TABLE)
                .createIfNotExist(false)
                .writeable(false)
                .build();
        }

        return PostgresSkillRepository.builder(dataSource)
            .schemaName(SKILL_SCHEMA)
            .skillsTableName(SKILL_TABLE)
            .resourcesTableName(SKILL_RESOURCE_TABLE)
            .createIfNotExist(false)
            .writeable(false)
            .build();
    }

}
