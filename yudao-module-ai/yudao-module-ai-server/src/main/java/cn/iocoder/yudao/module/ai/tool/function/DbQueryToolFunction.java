package cn.iocoder.yudao.module.ai.tool.function;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP 工具:业务数据库只读查询(带租户隔离 + 安全白名单)
 *
 * <p>通过 Spring AI MCP Server 自动发布到 /sse 端点,
 * LangChain / Claude Desktop / Cursor 等 MCP 客户端可直接发现并调用。
 *
 * <p>设计要点:
 * <ul>
 *   <li>每个对外方法加 {@link Tool} 注解,MCP 扫描器才能识别</li>
 *   <li>每个参数加 {@link ToolParam} 注解,声明描述/必填,生成 JSON Schema</li>
 *   <li>表名硬编码白名单,LLM 无法探测未授权表</li>
 *   <li>强制 LIMIT + 参数化 SQL,防慢 SQL/注入</li>
 *   <li>ToolContext 自动注入 tenantId + LoginUser,数据隔离</li>
 * </ul>
 *
 * @author 芋道源码
 */
@Component
@Slf4j
public class DbQueryToolFunction {

    /** 只允许查这几张表。白名单之外的表名一律拒绝 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "trade_order",    // 交易订单
            "member_user",    // 会员
            "product_sku",    // 商品 SKU
            "pay_order"       // 支付订单
    );

    /** 单次最多返回行数 */
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ============================================================
    // 工具 1:通用业务查询(白名单表)
    // ============================================================

    @Tool(
            name = "db_query",
            description = """
                    查询业务数据库(只读,自动按当前登录用户的租户隔离)。
                    支持 trade_order(交易订单)/ member_user(会员)/ product_sku(商品SKU)/ pay_order(支付订单) 四张表。
                    返回最多 limit 行,默认 20,最大 100。
                    """
    )
    public Response query(
            @ToolParam(description = "业务表名,只能是:trade_order、member_user、product_sku、pay_order 之一", required = true)
            String table,

            @ToolParam(description = "状态过滤条件,例如 paid/unpaid/shipped/finished。trade_order 和 pay_order 表适用", required = false)
            @Nullable
            String status,

            @ToolParam(description = "模糊匹配关键词,作用于订单号(order_no)或商品名称(name)", required = false)
            @Nullable
            String keyword,

            @ToolParam(description = "返回行数,默认 20,最大 100", required = false)
            @Nullable
            Integer limit,

            ToolContext toolContext
    ) {
        // ========== 1. 取上下文(tenantId + LoginUser) ==========
        Long tenantId = (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID);
        LoginUser loginUser = (LoginUser) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_LOGIN_USER);

        if (tenantId == null || loginUser == null) {
            log.warn("[db_query] 缺少租户或用户上下文,拒绝查询");
            return Response.error("无登录上下文,拒绝执行");
        }

        // ========== 2. 表名白名单 ==========
        if (StrUtil.isBlank(table) || !ALLOWED_TABLES.contains(table)) {
            return Response.error("表名 " + table + " 不在白名单,允许:" + ALLOWED_TABLES);
        }

        // ========== 3. 拼 SQL(强制 tenant_id + LIMIT) ==========
        int rowLimit = Optional.ofNullable(limit)
                .map(l -> Math.min(Math.max(l, 1), MAX_LIMIT))
                .orElse(DEFAULT_LIMIT);

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table)
                .append(" WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (StrUtil.isNotBlank(status)) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (StrUtil.isNotBlank(keyword)) {
            sql.append(" AND (order_no LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(rowLimit);

        log.info("[db_query] tenant={} user={} sql={} params={}",
                tenantId, loginUser.getId(), sql, params);

        // ========== 4. 执行 + 多租户隔离 ==========
        return TenantUtils.execute(tenantId, () -> {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        sql.toString(), params.toArray());
                String summary = String.format("表 %s 返回 %d 行(上限 %d)",
                        table, rows.size(), rowLimit);
                return Response.ok(rows, summary, tenantId);
            } catch (Exception e) {
                log.error("[db_query] 执行失败 sql={}", sql, e);
                return Response.error("查询失败:" + e.getMessage());
            }
        });
    }

    // ============================================================
    // 工具 2:订单统计(聚合查询,比通用查询更省 token)
    // ============================================================

    @Tool(
            name = "db_order_statistics",
            description = """
                    统计订单数据,返回聚合结果(总金额、订单数)。
                    适用于"上周销售额多少""今天下了多少单"这类问题。
                    自动按当前登录用户的租户隔离。
                    """
    )
    public Response orderStatistics(
            @ToolParam(description = "统计时间范围,只能是: today / week / month 之一", required = true)
            String range,

            @ToolParam(description = "订单状态过滤,例如 paid/refunded/all(默认 paid)", required = false)
            @Nullable
            String status,

            ToolContext toolContext
    ) {
        Long tenantId = (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID);
        if (tenantId == null) {
            return Response.error("无租户上下文,拒绝执行");
        }

        String interval = switch (range == null ? "" : range.toLowerCase()) {
            case "today" -> "INTERVAL 1 DAY";
            case "week"  -> "INTERVAL 7 DAY";
            case "month" -> "INTERVAL 30 DAY";
            default -> null;
        };
        if (interval == null) {
            return Response.error("range 参数只能是 today/week/month");
        }

        String orderStatus = StrUtil.blankToDefault(status, "paid");
        String sql = """
                SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS total_amount
                FROM trade_order
                WHERE tenant_id = ?
                  AND status = ?
                  AND create_time >= DATE_SUB(NOW(), %s)
                """.formatted(interval);

        log.info("[db_order_statistics] tenant={} sql={}", tenantId, sql);

        return TenantUtils.execute(tenantId, () -> {
            try {
                Map<String, Object> row = jdbcTemplate.queryForMap(sql, tenantId, orderStatus);
                String summary = String.format("%s 内 %s 订单共 %s 单,总金额 %s 元",
                        range, orderStatus,
                        row.get("order_count"), row.get("total_amount"));
                return Response.ok(List.of(row), summary, tenantId);
            } catch (Exception e) {
                log.error("[db_order_statistics] 执行失败", e);
                return Response.error("统计失败:" + e.getMessage());
            }
        });
    }

    // ============================================================
    // 响应结构
    // ============================================================

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonClassDescription("数据库查询结果")
    public static class Response {
        private boolean success;
        private List<Map<String, Object>> rows;
        private Integer rowCount;
        private String summary;
        private Long tenantId;
        private String error;

        public static Response ok(List<Map<String, Object>> rows, String summary, Long tenantId) {
            return new Response(true, rows, rows.size(), summary, tenantId, null);
        }

        public static Response error(String msg) {
            return new Response(false, List.of(), 0, null, null, msg);
        }
    }
}
