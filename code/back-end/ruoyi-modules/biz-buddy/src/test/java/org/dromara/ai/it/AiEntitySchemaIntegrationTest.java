package org.dromara.ai.it;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 集成测试：**AI 实体 ↔ 真库 schema 一致性**（PostgreSQL 栈 / MySQL 栈各自独立判定）。
 *
 * <p><b>为什么需要</b>：两方言互相比较（{@link AiDbDialectIntegrationTest}）只能发现「只改一边」；
 * 若**代码加了字段、两个建库脚本都忘了加列**，两栈互为镜像、方言对比会放过。本测试改以
 * **实体元数据**为准：把 {@code org.dromara.ai.domain} 下带 {@code @TableName} 的实体，按
 * MyBatis-Plus 的列映射规则（{@code @TableId} / {@code @TableField} 注解值优先，缺省按驼峰转下划线，
 * 且 {@code exist=false} 不计）算出期望列，逐项对照**真实库**的表与列，缺表 / 缺列即失败——即
 * 「运行期 MyBatis-Plus 会拼出不存在的列」这一真实缺陷面。</p>
 *
 * <p><b>默认跳过</b>：设环境变量 {@code BIZBUDDY_IT=true} 才执行；坐标可用 {@code BIZBUDDY_IT_PG_URL} /
 * {@code BIZBUDDY_IT_MYSQL_URL} 等覆盖。**任一栈连上即执行该栈**（不要求两栈同时就绪），两栈都连不上
 * 才整类跳过。</p>
 *
 * <p><b>范围</b>：只断言「实体有、库里缺」；不做反向（库里有、实体没映射）——治理列与框架列
 * （如 {@code created_at}）本就允许无实体字段。存量库手工 ALTER 未跟上的缺列也会被本测试暴露。</p>
 *
 * @author BizBuddy
 */
@EnabledIfEnvironmentVariable(named = "BIZBUDDY_IT", matches = "(?i)true")
@DisplayName("集成测试：AI 实体 ↔ 真库 schema 一致性")
class AiEntitySchemaIntegrationTest {

    /**
     * AI 实体所在包
     */
    private static final String ENTITY_PACKAGE = "org.dromara.ai.domain";

    /**
     * 表名前缀：BizBuddy AI 业务表
     */
    private static final String TABLE_PREFIX = "ai_";

    /**
     * 已连上的栈（连不上的不入列）
     */
    private static final List<Stack> STACKS = new ArrayList<>();

    /**
     * 扫描到的 AI 实体
     */
    private static List<Class<?>> entities;

    @BeforeAll
    static void connectStacks() throws Exception {
        openStack("PostgreSQL", env("BIZBUDDY_IT_PG_URL", "jdbc:postgresql://localhost:5432/biz_buddy"),
            env("BIZBUDDY_IT_PG_USER", "postgres"), env("BIZBUDDY_IT_PG_PASSWORD", "Zl@2026"), "public");
        openStack("MySQL", env("BIZBUDDY_IT_MYSQL_URL",
                "jdbc:mysql://localhost:3306/biz_buddy?useUnicode=true&characterEncoding=utf8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"),
            env("BIZBUDDY_IT_MYSQL_USER", "root"), env("BIZBUDDY_IT_MYSQL_PASSWORD", "Zl@2026"), null);

        assumeTrue(!STACKS.isEmpty(), "两栈均未就绪（PG 5432 / MySQL 3306），跳过实体↔schema 集成测试");
        entities = scanEntities();
        assumeTrue(!entities.isEmpty(), "未扫描到任何 AI 实体（" + ENTITY_PACKAGE + "）");
    }

    @AfterAll
    static void closeStacks() {
        for (Stack stack : STACKS) {
            try {
                stack.connection().close();
            } catch (SQLException ignored) {
                // 关闭失败不影响测试结论
            }
        }
    }

    @Test
    @DisplayName("每个 AI 实体的表与列都在真库中存在")
    void shouldMatchEntityColumnsWithRealSchema() {
        long scanned = entities.stream().filter(entity -> tableName(entity).startsWith(TABLE_PREFIX)).count();
        assertTrue(scanned >= 30,
            "扫描到的 AI 实体数异常（" + scanned + "）：实体包结构可能已变，测试未真正比对上");

        List<String> failures = new ArrayList<>();
        for (Stack stack : STACKS) {
            Set<String> tables = aiTables(stack);
            Map<String, Set<String>> columns = aiColumns(stack);
            for (Class<?> entity : entities) {
                String table = tableName(entity);
                if (!table.startsWith(TABLE_PREFIX)) {
                    continue;
                }
                if (!tables.contains(table)) {
                    failures.add(stack.label() + "：" + entity.getSimpleName() + " 期望表 " + table + " 不存在");
                    continue;
                }
                Set<String> realColumns = columns.getOrDefault(table, Set.of());
                for (String column : expectedColumns(entity)) {
                    if (!realColumns.contains(column)) {
                        failures.add(stack.label() + "：" + table + "." + column
                            + "（" + entity.getSimpleName() + "）在真库中不存在");
                    }
                }
            }
        }
        List<String> preview = failures.stream().limit(50).toList();
        assertTrue(failures.isEmpty(), "实体 ↔ schema 不一致（实体已变更而建库脚本 / 存量库缺列）：\n  "
            + String.join("\n  ", preview) + (failures.size() > preview.size() ? "\n  …共 " + failures.size() + " 项" : ""));
    }

    /**
     * 记录连上的一个栈。
     *
     * @param label       栈名（仅用于失败信息）
     * @param connection  连接
     * @param schema      schema / 库名（PG 用 {@code public}，MySQL 用当前 catalog）
     */
    private record Stack(String label, Connection connection, String schema) {
    }

    /**
     * 尝试连接一个栈；连不上则不入列（由 {@code assumeTrue} 统一决定是否跳过）。
     *
     * @param label    栈名
     * @param url      连接串
     * @param user     用户名
     * @param password 密码
     * @param schema   schema / 库名；{@code null} 表示取连接的 catalog
     */
    private static void openStack(String label, String url, String user, String password, String schema) {
        try {
            Connection connection = DriverManager.getConnection(url, user, password);
            String realSchema = schema != null ? schema : connection.getCatalog();
            STACKS.add(new Stack(label, connection, realSchema));
        } catch (Exception ignored) {
            // 连不上：该栈不参与，避免单栈未起时误报失败
        }
    }

    /**
     * 扫描 {@link #ENTITY_PACKAGE} 下所有带 {@code @TableName} 的具体实体类。
     *
     * @return 实体类列表（按类名排序）
     * @throws Exception 扫描异常
     */
    private static List<Class<?>> scanEntities() throws Exception {
        ClassLoader loader = AiEntitySchemaIntegrationTest.class.getClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        Enumeration<URL> urls = loader.getResources(ENTITY_PACKAGE.replace('.', '/'));
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (!"file".equals(url.getProtocol())) {
                continue;
            }
            File[] files = new File(url.toURI()).listFiles(
                (dir, name) -> name.endsWith(".class") && !name.contains("$"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
                Class<?> clazz = Class.forName(ENTITY_PACKAGE + "." + simpleName, false, loader);
                if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                    continue;
                }
                if (!clazz.isAnnotationPresent(TableName.class)) {
                    continue;
                }
                classes.add(clazz);
            }
        }
        classes.sort(Comparator.comparing(Class::getSimpleName));
        return classes;
    }

    /**
     * 取实体的表名：{@code @TableName} 注解值优先，缺省按类名驼峰转下划线。
     *
     * @param entity 实体类
     * @return 表名（小写）
     */
    private static String tableName(Class<?> entity) {
        TableName annotation = entity.getAnnotation(TableName.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value().toLowerCase(Locale.ROOT);
        }
        return camelToUnderline(entity.getSimpleName());
    }

    /**
     * 按 MyBatis-Plus 的映射规则算出实体期望的列集合（含父类字段；跳过静态 / 瞬态 / {@code exist=false}）。
     *
     * @param entity 实体类
     * @return 列名集合（小写）
     */
    private static Set<String> expectedColumns(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Class<?> current = entity; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                    continue;
                }
                TableField tableField = field.getAnnotation(TableField.class);
                if (tableField != null && !tableField.exist()) {
                    continue;
                }
                TableId tableId = field.getAnnotation(TableId.class);
                String column;
                if (tableId != null && !tableId.value().isBlank()) {
                    column = tableId.value();
                } else if (tableField != null && !tableField.value().isBlank()) {
                    column = tableField.value();
                } else {
                    column = camelToUnderline(field.getName());
                }
                columns.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    /**
     * 驼峰转下划线（与 MyBatis-Plus {@code table-underline} 默认策略一致）。
     *
     * @param name 驼峰名
     * @return 下划线名（小写）
     */
    private static String camelToUnderline(String name) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(ch));
        }
        return builder.toString();
    }

    /**
     * 取栈内 AI 业务表名集合。
     *
     * @param stack 栈
     * @return 表名集合（小写）
     */
    private static Set<String> aiTables(Stack stack) {
        Set<String> tables = new TreeSet<>();
        String sql = "select table_name from information_schema.tables where table_schema = '"
            + stack.schema() + "'";
        queryRows(stack.connection(), sql, resultSet -> {
            String name = resultSet.getString(1);
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(TABLE_PREFIX)) {
                tables.add(name.toLowerCase(Locale.ROOT));
            }
        });
        return tables;
    }

    /**
     * 取栈内 AI 业务表的列（{@code 表名 → 列名集合}，均小写）。
     *
     * @param stack 栈
     * @return 表名到列集合的映射
     */
    private static Map<String, Set<String>> aiColumns(Stack stack) {
        Map<String, Set<String>> columns = new TreeMap<>();
        String sql = "select table_name, column_name from information_schema.columns where table_schema = '"
            + stack.schema() + "'";
        queryRows(stack.connection(), sql, resultSet -> {
            String table = resultSet.getString(1);
            String column = resultSet.getString(2);
            if (table != null && column != null && table.toLowerCase(Locale.ROOT).startsWith(TABLE_PREFIX)) {
                columns.computeIfAbsent(table.toLowerCase(Locale.ROOT), key -> new TreeSet<>())
                    .add(column.toLowerCase(Locale.ROOT));
            }
        });
        return columns;
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
