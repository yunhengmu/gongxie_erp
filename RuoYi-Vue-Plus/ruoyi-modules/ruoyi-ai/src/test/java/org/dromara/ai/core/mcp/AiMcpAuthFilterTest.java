package org.dromara.ai.core.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiMcpAuthFilter} 单元测试：口令校验与放行 / 拒绝分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MCP 出口口令校验过滤器")
class AiMcpAuthFilterTest {

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private AiMcpAuthFilter filter;
    private PrintWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AiMcpAuthFilter(runtimeConfigStore);
        writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);
    }

    private void config(boolean authEnabled, String token) {
        AiMcpServerConfigBo bo = new AiMcpServerConfigBo();
        bo.setAuthEnabled(authEnabled);
        bo.setToken(token);
        when(runtimeConfigStore.mcpServer()).thenReturn(bo);
    }

    @Test
    @DisplayName("非 HTTP 请求直接放行")
    void shouldPassThroughNonHttpRequest() throws Exception {
        ServletRequest rawRequest = mock(ServletRequest.class);
        ServletResponse rawResponse = mock(ServletResponse.class);

        filter.doFilter(rawRequest, rawResponse, chain);

        verify(chain).doFilter(rawRequest, rawResponse);
    }

    @Test
    @DisplayName("未开启口令校验时放行")
    void shouldPassWhenAuthDisabled() throws Exception {
        config(false, null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("开启校验但未配置口令时拒绝所有请求")
    void shouldRejectWhenTokenMissing() throws Exception {
        config(true, "  ");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(capturedBody().contains("尚未配置访问口令"));
    }

    @Test
    @DisplayName("Authorization Bearer 口令正确时放行")
    void shouldPassWithBearerToken() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn("Bearer secret");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-MCP-Token 口令正确时放行")
    void shouldPassWithMcpTokenHeader() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader(AiMcpAuthFilter.HEADER_MCP_TOKEN)).thenReturn("secret");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 未带 Bearer 前缀且与口令一致时放行")
    void shouldPassWithRawAuthorization() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn("secret");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer 前缀大小写不敏感")
    void shouldAcceptBearerCaseInsensitive() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn("bearer secret");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("口令错误时拒绝并输出 JSON-RPC 错误体")
    void shouldRejectWrongToken() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn("Bearer wrong");
        when(request.getRequestURI()).thenReturn("/mcp");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        String body = capturedBody();
        assertTrue(body.contains("缺失或错误"));
        assertTrue(body.contains("-32001"));
    }

    @Test
    @DisplayName("未携带任何口令时拒绝")
    void shouldRejectWhenNoTokenProvided() throws Exception {
        config(true, "secret");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader(AiMcpAuthFilter.HEADER_MCP_TOKEN)).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertTrue(capturedBody().contains("缺失或错误"));
    }

    private String capturedBody() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).write(captor.capture());
        return captor.getValue();
    }

}