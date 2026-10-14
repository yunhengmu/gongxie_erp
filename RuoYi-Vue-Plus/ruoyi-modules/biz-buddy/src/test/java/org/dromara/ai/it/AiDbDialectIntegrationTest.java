package org.dromara.ai.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 集成测试：**两方言真库**的 schema 与菜单一致性（PostgreSQL 栈 + MySQL 栈）。
 *
 * <p><b>为什么需要</b>：两套建库脚本（{@code script/sql/ry_ai.sql} 与 {@code script/sql/postgres/postgres_ry_ai.sql}）
 * 各自演进，历史上多次出现「只改一边」——表 / 列 / 索引 / 按钮权限在另一方言缺失，且**单测（全 mock）
 * 抓不到、端到端测试只在单个栈上跑也抓不到**。本测试把两个栈的**真实 schema** 拉出来逐项对比。</p>
 *
 * <p><b>默认跳过</b>：需要两栈同时运行（PG {@code 5432} + MySQL {@code 3306}）。设环境变量
 * {@code BIZBUDDY_IT=true} 才执行；坐标可用 {@code BIZBUDDY_IT_PG_URL} / {@code BIZBUDDY_IT_MYSQL_URL}
 * 等覆盖（见 README/文档）。任一栈连不上即整类跳过，不误报失败。</p>
 *
 * @author BizBuddy
 */
@EnabledIfEnvironmentVariable(named = "BIZBUDDY_IT", matches = "(?i)true")
@DisplayName("集成测试：两方言真库 schema 与菜单一致性")
class AiDbDialectIntegrationTest {

    /**
     * 表名前缀：BizBuddy AI 业务表
     */
    private static final String TABLE_PREFIX = "ai_";

    /**
     * 索引名前缀：本平台显式命名（自动主键 / 唯一索引名在两方言下不同，故不参与对比）
     */
    private static final String INDEX_PREFIX = "idx_ai_";

    /**
     * §95 新增的 6 个 create_time 索引：两方言都必须存在
     */
    private static final List<String> CREATE_TIME_INDEXES = List.of(
        "idx_ai_trace_create_time",
        "idx_ai_tool_call_log_create_time",
        "idx_ai_perm_log_create_time",
        "idx_ai_agent_task_run_create_time",
        "idx_ai_route_run_create_time",
        "idx_ai_route_expert_call_create_time");

    private static Connection pg;

    private static Connection mysql;

    private static String pgSchema;

    private static String mysqlSchema;

    @BeforeAll
    static void connectBothStacks() {
        pgSchema = "public";
        mysqlSchema = "biz_buddy";
        pg = connect(env("BIZBUDDY_IT_PG_URL", "jdbc:postgresql://localhost:5432/biz_buddy"),
            env("BIZBUDDY_IT_PG_USER", "postgres"), env("BIZBUDDY_IT_PG_PASSWORD", "Zl@2026"));
        mysql = connect(env("BIZBUDDY_IT_MYSQL_URL",
                "jdbc:mysql://localhost:3306/biz_buddy?useUnicode=true&characterEncoding=utf8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
            env("BIZBUDDY_IT_MYSQL_USER", "root"), env("BIZBUDDY_IT_MYSQL_PASSWORD", "Zl@2026"));
        assumeTrue(pg != null && mysql != null,
            "两栈未同时就绪（PG 5432 / MySQL 3306）或缺少 JDBC 驱动，跳过集成测试");
        mysqlSchema = queryString(mysql, "select database()");
    }

    @AfterAll
    static void closeConnections() {
        close(pg);
        close(mysql);
    }

    @Test
    @DisplayName("AI 业务表清单一致")
    void shouldHaveSameAiTables() {
        Set<String> pgTables = aiTables(pg, pgSchema);
        Set<String> mysqlTables = aiTables(mysql, mysqlSchema);

        assertTrue(pgTables.size() > 30, "PG 栈 AI 表数量异常（" + pgTables.size() + "）：建库脚本可能未执行");
        assertEquals(pgTables, mysqlTables, "两方言 AI 表清单不一致：" + diff(pgTables, mysqlTables));
    }

    @Test
    @DisplayName("AI 业务表的列清单一致")
    void shouldHaveSameAiColumns() {
        Set<String> pgColumns = aiColumns(pg, pgSchema);
        Set<String> mysqlColumns = aiColumns(mysql, mysqlSchema);

        assertEquals(pgColumns, mysqlColumns, "两方言列清单不一致：" + diff(pgColumns, mysqlColumns));
    }

    @Test
    @DisplayName("显式命名的索引清单一致，且 6 个 create_time 索引两方言都在")
    void shouldHaveSameExplicitIndexes() {
        Set<String> pgIndexes = aiIndexes(pg, pgSchema);
        Set<String> mysqlIndexes = aiIndexes(mysql, mysqlSchema);

        assertEquals(pgIndexes, mysqlIndexes, "两方言索引清单不一致：" + diff(pgIndexes, mysqlIndexes));
        for (String index : CREATE_TIME_INDEXES) {
            assertTrue(pgIndexes.contains(index), "PG 栈缺少 create_time 索引：" + index);
            assertTrue(mysqlIndexes.contains(index), "MySQL 栈缺少 create_time 索引：" + index);
        }
    }

    @Test
    @DisplayName("AI 相关菜单 / 按钮权限标识一致")
    void shouldHaveSameAiMenuPerms() {
        Set<String> pgPerms = aiMenuPerms(pg);
        Set<String> mysqlPerms = aiMenuPerms(mysql);

        assertTrue(pgPerms.size() > 50, "PG 栈 AI 菜单权限数量异常（" + pgPerms.size() + "）：建库脚本可能未执行");
        assertEquals(pgPerms, mysqlPerms, "两方言菜单权限不一致：" + diff(pgPerms, mysqlPerms));
    }

    /**
     * 取 AI 业务表名（小写）。
     *
     * @param connection 连接
     * @param schema     库 / 模式名
     * @return 表名集合
     */
    private static Set<String> aiTables(Connection connection, String schema) {
        Set<String> tables = new TreeSet<>();
        String sql = "select table_name from information_schema.tables where table_schema = '" + schema + "'";
        queryRows(connection, sql, resultSet -> {
            String name = resultSet.getString(1);
            if (name != null && name.toLowerCase().startsWith(TABLE_PREFIX)) {
                tables.add(name.toLowerCase());
            }
        });
        return tables;
    }

    /**
     * 取 AI 业务表的列（{@code 表名.列名}，均小写）。
     *
     * @param connection 连接
     * @param schema     库 / 模式名
     * @return 列集合
     */
    private static Set<String> aiColumns(Connection connection, String schema) {
        Set<String> columns = new TreeSet<>();
        String sql = "select table_name, column_name from information_schema.columns where table_schema = '"
            + schema + "'";
        queryRows(connection, sql, resultSet -> {
            String table = resultSet.getString(1);
            String column = resultSet.getString(2);
            if (table != null && table.toLowerCase().startsWith(TABLE_PREFIX)) {
                columns.add(table.toLowerCase() + "." + column.toLowerCase());
            }
        });
        return columns;
    }

    /**
     * 取显式命名的 AI 索引（{@code idx_ai_} 前缀；主键 / 唯一索引名两方言不同，不纳入）。
     *
     * @param connection 连接
     * @param schema     库 / 模式名
     * @return 索引名集合
     */
    private static Set<String> aiIndexes(Connection connection, String schema) {
        Set<String> indexes = new TreeSet<>();
        String sql = connection == pg
            ? "select indexname from pg_indexes where schemaname = '" + schema + "'"
            : "select distinct index_name from information_schema.statistics where table_schema = '"
                + schema + "'";
        queryRows(connection, sql, resultSet -> {
            String name = resultSet.getString(1);
            if (name != null && name.toLowerCase().startsWith(INDEX_PREFIX)) {
                indexes.add(name.toLowerCase());
            }
        });
        return indexes;
    }

    /**
     * 取 AI 相关菜单 / 按钮的权限标识（{@code ai:} 前缀）。
     *
     * @param connection 连接
     * @return 权限标识集合
     */
    private static Set<String> aiMenuPerms(Connection connection) {
        Set<String> perms = new TreeSet<>();
        queryRows(connection, "select perms from sys_menu where perms like 'ai:%'", resultSet -> {
            String perm = resultSet.getString(1);
            if (perm != null && !perm.isBlank()) {
                perms.add(perm.trim());
            }
        });
        return perms;
    }

    /**
     * 逐行消费查询结果。
     *
     * @param connection 连接
     * @param sql        SQL
     * @param consumer   行处理器
     */
    private static void queryRows(Connection connection, String sql, RowConsumer consumer) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                consumer.accept(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    /**
     * 取单值查询结果。
     *
     * @param connection 连接
     * @param sql        SQL
     * @return 首行首列；无结果时为 null
     */
    private static String queryString(Connection connection, String sql) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败：" + sql, e);
        }
    }

    /**
     * 建立连接；失败返回 {@code null}（由 {@code assumeTrue} 统一跳过）。
     *
     * @param url      连接串
     * @param user     用户名
     * @param password 密码
     * @return 连接；失败为 null
     */
    private static Connection connect(String url, String user, String password) {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 静默关闭连接。
     *
     * @param connection 连接
     */
    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败不影响测试结论
            }
        }
    }

    /**
     * 渲染两个集合的差异（各自最多列 20 项）。
     *
     * @param left  左集合
     * @param right 右集合
     * @return 差异描述
     */
    private static String diff(Set<String> left, Set<String> right) {
        Set<String> onlyPg = new LinkedHashSet<>(left);
        onlyPg.removeAll(right);
        Set<String> onlyMysql = new LinkedHashSet<>(right);
        onlyMysql.removeAll(left);
        return "PG 独有 " + render(onlyPg) + "；MySQL 独有 " + render(onlyMysql);
    }

    /**
     * 渲染集合（最多 20 项后省略）。
     *
     * @param values 集合
     * @return 渲染结果
     */
    private static String render(Set<String> values) {
        if (values.isEmpty()) {
            return "（无）";
        }
        List<String> items = values.stream().limit(20).toList();
        return items + (values.size() > items.size() ? " 等 " + values.size() + " 项" : "");
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
     * 结果集行处理器。
     */
    @FunctionalInterface
    private interface RowConsumer {

        /**
         * 处理一行。
         *
         * @param resultSet 结果集
         * @throws SQLException 读取异常
         */
        void accept(ResultSet resultSet) throws SQLException;
    }

}
