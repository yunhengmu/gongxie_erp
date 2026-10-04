package org.dromara.ai.core.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.common.core.utils.StringUtils;

import java.io.IOException;

/**
 * MCP 出口（{@code /mcp}）访问口令校验过滤器（REQ-AI-15）。
 *
 * <p>MCP 出口与主应用共用端口，且出口调用方是**外部 AI 系统**（无平台登录态），
 * 故不放行 Sa-Token 登录校验，改用**单一平台口令**：请求需携带
 * {@code Authorization: Bearer <token>} 或 {@code X-MCP-Token: <token>}，
 * 与「MCP 服务端」页配置的运行期口令一致才放行。</p>
 *
 * <p>口令校验开关与口令均为平台级运行期配置（Redis），保存后立即生效，无需重启。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
public class AiMcpAuthFilter implements Filter {

    /**
     * 备选口令请求头（便于不便使用 Authorization 的调用方）
     */
    public static final String HEADER_MCP_TOKEN = "X-MCP-Token";

    /**
     * 认证头前缀
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private final AiRuntimeConfigStore runtimeConfigStore;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
            || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        AiMcpServerConfigBo config = runtimeConfigStore.mcpServer();
        if (!config.isAuthEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String expected = StringUtils.trim(config.getToken());
        if (StringUtils.isBlank(expected)) {
            reject(httpResponse, "MCP 出口已开启口令校验但尚未配置访问口令，请先到「MCP 服务端」页配置");
            return;
        }
        String provided = resolveToken(httpRequest);
        if (!expected.equals(provided)) {
            log.warn("MCP 出口请求口令校验失败：uri={}, remote={}", httpRequest.getRequestURI(),
                httpRequest.getRemoteAddr());
            reject(httpResponse, "MCP 访问口令缺失或错误");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 从请求中解析口令：优先 {@code Authorization: Bearer}，其次 {@code X-MCP-Token}。
     *
     * @param request HTTP 请求
     * @return 口令（未携带时返回空串）
     */
    private String resolveToken(HttpServletRequest request) {
        String authorization = StringUtils.trim(request.getHeader("Authorization"));
        if (StringUtils.isNotBlank(authorization)) {
            if (authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return StringUtils.trim(authorization.substring(BEARER_PREFIX.length()));
            }
            return authorization;
        }
        return StringUtils.trim(request.getHeader(HEADER_MCP_TOKEN));
    }

    /**
     * 以 JSON-RPC 错误结构拒绝请求（MCP 客户端可正确解析该响应体）。
     *
     * @param response HTTP 响应
     * @param message  错误提示
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 手工拼装，避免为一句错误提示引入额外序列化依赖
        String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\""
            + StringUtils.trim(message).replace("\"", "'") + "\"}}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

}