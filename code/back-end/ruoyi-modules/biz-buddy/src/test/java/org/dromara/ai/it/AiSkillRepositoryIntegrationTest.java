package org.dromara.ai.it;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import org.dromara.ai.core.agent.AgentScopeConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 集成测试：**AgentScope 技能仓库 ↔ 真库读写**（两方言装配分支）。
 *
 * <p><b>为什么需要</b>：技能仓库 Bean 由 {@link AgentScopeConfig#aiSkillRepository(DataSource)}
 * **按数据源的数据库产品名二选一装配**（PostgreSQL / MySQL 两套框架实现，库名语义不同：PG 用 schema、
 * MySQL 用 catalog），且框架对 {@code ai_skill} / {@code ai_skill_resource} 的**列结构有硬性兼容要求**。
 * 单测把 {@code AgentSkillRepository} 全 mock、端到端测试没有技能层——这条「真库读路径」此前零覆盖。</p>
 *
 * <p><b>做法</b>：直接调用**生产装配代码**（{@code new AgentScopeConfig().aiSkillRepository(dataSource)}）
 * 建出仓库，再用一条临时技能（含 body / 参考资料 / {@code metadata_json}）走一遍
 * 「写（裸 JDBC）→ 读（框架仓库）」，收尾删除。仓库以只读装配，故 {@code save} 应返回 false。</p>
 *
 * <p><b>默认跳过</b>：设环境变量 {@code BIZBUDDY_IT=true} 才执行；坐标可用 {@code BIZBUDDY_IT_PG_URL} /
 * {@code BIZBUDDY_IT_MYSQL_URL} 等覆盖。**任一栈连上即执行该栈**，两栈都连不上才整类跳过。</p>
 *
 * @author BizBuddy
 */
@EnabledIfEnvironmentVariable(named = "BIZBUDDY_IT", matches = "(?i)true")
@DisplayName("集成测试：AgentScope 技能仓库 ↔ 真库读写（两方言装配分支）")
class AiSkillRepositoryIntegrationTest {

    /**
     * 技能表名（与 {@link AgentScopeConfig} 装配口径一致）
     */
    private static final String SKILL_TABLE = "ai_skill";

    /**
     * 技能参考资料表名
     */
    private static final String SKILL_RESOURCE_TABLE = "ai_skill_resource";

    /**
     * 临时技能主键基数（取高段避开雪花 id，且不越 bigint 上限）
     */
    private static final long TEST_ID_BASE = 9_000_000_000_000_000_000L;

    /**
     * 已连上的栈（连不上的不入列）
     */
    private static final List<Stack> STACKS = new ArrayList<>();

    @BeforeAll
    static void connectStacks() {
        openStack("PostgreSQL", env("BIZBUDDY_IT_PG_URL", "jdbc:postgresql://localhost:5432/biz_buddy"),
            env("BIZBUDDY_IT_PG_USER", "postgres"), env("BIZBUDDY_IT_PG_PASSWORD", "Zl@2026"), "postgresql_");
        openStack("MySQL", env("BIZBUDDY_IT_MYSQL_URL",
                "jdbc:mysql://localhost:3306/biz_buddy?useUnicode=true&characterEncoding=utf8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
            env("BIZBUDDY_IT_MYSQL_USER", "root"), env("BIZBUDDY_IT_MYSQL_PASSWORD", "Zl@2026"), "mysql_");

        assumeTrue(!STACKS.isEmpty(), "两栈均未就绪（PG 5432 / MySQL 3306），跳过技能仓库集成测试");
    }

    @Test
    @DisplayName("按方言装配出只读技能仓库，并能按 name 读回正文、参考资料与 metadata")
    void shouldAssembleByDialectAndReadBackSkill() throws Exception {
        for (Stack stack : STACKS) {
            verifyStack(stack);
        }
    }

    /**
     * 在单个栈上验证：装配分支正确 + 读路径与真库列结构兼容。
     *
     * @param stack 栈
     * @throws Exception JDBC 异常
     */
    private static void verifyStack(Stack stack) throws Exception {
        DataSource dataSource = new DriverDataSource(stack.url(), stack.user(), stack.password);

        // 走生产装配逻辑（按 databaseProductName 二选一），而非在测试里复刻一份
        AgentSkillRepository repository = new AgentScopeConfig().aiSkillRepository(dataSource);

        assertFalse(repository.isWriteable(), stack.label() + "：技能仓库应为只读装配");
        assertTrue(repository.getSource().startsWith(stack.sourcePrefix()),
            stack.label() + "：仓库来源应为 " + stack.sourcePrefix() + " 开头，实际 " + repository.getSource());

        String name = "it_skill_" + System.nanoTime();
        long id = TEST_ID_BASE + ThreadLocalRandom.current().nextLong(1_000_000L);
        String content = "# " + name + "\n\n集成测试用 SKILL.md 正文。";
        String resourcePath = "references/faq.md";
        String resourceContent = "参考资料内容 " + name;
        String metadataJson = "{\"version\":\"1.0\"}";

        try (Connection connection = dataSource.getConnection()) {
            insertSkill(connection, id, name, content, metadataJson);
            insertResource(connection, id, resourcePath, resourceContent);
        }

        try {
            assertTrue(repository.getAllSkillNames().contains(name),
                stack.label() + "：getAllSkillNames 应包含新写入的技能");
            assertTrue(repository.skillExists(name), stack.label() + "：skillExists 应为 true");
            assertFalse(repository.skillExists(name + "_absent"),
                stack.label() + "：不存在的技能名 skillExists 应为 false");

            AgentSkill skill = repository.getSkill(name);
            assertEquals(name, skill.getName(), stack.label() + "：读回技能名不一致");
            assertEquals(content, skill.getSkillContent(), stack.label() + "：读回 SKILL.md 正文不一致");
            assertEquals(resourceContent, skill.getResources().get(resourcePath),
                stack.label() + "：参考资料应从 ai_skill_resource 按 id 读回");
            assertEquals("1.0", skill.getMetadata().get("version"),
                stack.label() + "：metadata_json 应被框架仓库读回");

            assertTrue(repository.getAllSkills().stream().anyMatch(item -> name.equals(item.getName())),
                stack.label() + "：getAllSkills 应包含新技能");

            assertFalse(repository.save(List.of(skill), false),
                stack.label() + "：只读仓库 save 应返回 false（不落库）");
        } finally {
            cleanup(stack, name, id);
        }
    }

    /**
     * 写入一条临时技能行（裸 JDBC，列名两方言一致）。
     *
     * @param connection 连接
     * @param id         主键
     * @param name       技能标识
     * @param content    SKILL.md 正文
     * @param metadataJson 元数据 JSON
     * @throws SQLException 写入异常
     */
    private static void insertSkill(Connection connection, long id, String name, String content, String metadataJson)
        throws SQLException {
        String sql = "insert into " + SKILL_TABLE
            + " (id, name, description, skill_content, source, metadata_json) values (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setString(3, "集成测试技能 " + name);
            statement.setString(4, content);
            statement.setString(5, "bizbuddy");
            statement.setString(6, metadataJson);
            statement.executeUpdate();
        }
    }

    /**
     * 写入一条临时参考资料行。
     *
     * @param connection      连接
     * @param id              所属技能主键
     * @param resourcePath    相对路径
     * @param resourceContent 文件内容
     * @throws SQLException 写入异常
     */
    private static void insertResource(Connection connection, long id, String resourcePath, String resourceContent)
        throws SQLException {
        String sql = "insert into " + SKILL_RESOURCE_TABLE + " (id, resource_path, resource_content) values (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, resourcePath);
            statement.setString(3, resourceContent);
            statement.executeUpdate();
        }
    }

    /**
     * 收尾清理临时技能与参考资料（失败也不影响测试结论）。
     *
     * @param stack 栈
     * @param name  技能标识
     * @param id    主键
     */
    private static void cleanup(Stack stack, String name, long id) {
        try (Connection connection = DriverManager.getConnection(stack.url(), stack.user(), stack.password())) {
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from " + SKILL_RESOURCE_TABLE + " where id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "delete from " + SKILL_TABLE + " where name = ? or id = ?")) {
                statement.setString(1, name);
                statement.setLong(2, id);
                statement.executeUpdate();
            }
        } catch (SQLException ignored) {
            // 清理失败（如连接已断）不影响测试结论
        }
    }

    /**
     * 记录连上的一个栈。
     *
     * @param label        栈名（仅用于失败信息）
     * @param url          连接串
     * @param user         用户名
     * @param password     密码
     * @param sourcePrefix 仓库来源前缀（PG {@code postgresql_} / MySQL {@code mysql_}）
     */
    private record Stack(String label, String url, String user, String password, String sourcePrefix) {
    }

    /**
     * 尝试连接一个栈；连不上则不入列（由 {@code assumeTrue} 统一决定是否跳过）。
     *
     * @param label        栈名
     * @param url          连接串
     * @param user         用户名
     * @param password     密码
     * @param sourcePrefix 仓库来源前缀
     */
    private static void openStack(String label, String url, String user, String password, String sourcePrefix) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            STACKS.add(new Stack(label, url, user, password, sourcePrefix));
        } catch (Exception ignored) {
            // 连不上：该栈不参与，避免单栈未起时误报失败
        }
    }

    /**
     * 取环境变量，缺省用默认值。
     *
     * @param key          变量名
     * @param defaultValue 默认值
     * @return 值
     */
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 依赖 {@link DriverManager} 的极简 {@link DataSource}：仅用于把连接串交给框架仓库，
     * 不引连接池依赖。
     */
    private static final class DriverDataSource implements DataSource {

        private final String url;

        private final String user;

        private final String password;

        private DriverDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // 无连接池，不记录日志
        }

        @Override
        public void setLoginTimeout(int seconds) {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("DriverDataSource 不支持 JUL 日志");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("DriverDataSource 不是任何接口的包装");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

}
