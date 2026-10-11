package org.dromara.ai.core;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiRerankClient} 单元测试：成功重排与各类失败场景的降级（HTTP 全部打桩，不发真实请求）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("文本重排客户端")
class AiRerankClientTest {

    private AiRerankClient client;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        client = new AiRerankClient(new AiProperties());
    }

    private HttpResponse response(int status, String body) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private HttpRequest requestWith(MockedStatic<HttpUtil> httpUtil, HttpResponse response) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyInt())).thenReturn(request);
        when(request.body(anyString())).thenReturn(request);
        when(request.execute()).thenReturn(response);
        httpUtil.when(() -> HttpUtil.createPost(anyString())).thenReturn(request);
        return request;
    }

    @Test
    @DisplayName("成功重排：按相关度降序返回下标与分数")
    void shouldRerankSuccessfully() {
        String body = "{\"output\":{\"results\":[{\"index\":2,\"relevance_score\":0.25},"
            + "{\"index\":0,\"relevance_score\":0.18}]},\"usage\":{\"total_tokens\":10}}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            AiRerankClient.RerankOutcome outcome = client.rerank("key", "q", List.of("a", "b", "c"), 3);
            assertFalse(outcome.degraded());
            assertEquals(2, outcome.docs().size());
            assertEquals(2, outcome.docs().get(0).index());
            assertEquals(0.25, outcome.docs().get(0).score());
            assertEquals(0, outcome.docs().get(1).index());
        }
    }

    @Test
    @DisplayName("未配密钥：降级为原顺序，不发请求")
    void shouldDegradeWhenNoKey() {
        AiRerankClient.RerankOutcome outcome = client.rerank(null, "q", List.of("a", "b"), 2);
        assertTrue(outcome.degraded());
        assertEquals(List.of(0, 1), outcome.docs().stream().map(AiRerankClient.RankedDoc::index).toList());
    }

    @Test
    @DisplayName("查询为空或候选为空：降级为原顺序")
    void shouldDegradeWhenInputBlank() {
        assertTrue(client.rerank("key", "  ", List.of("a"), 1).degraded());
        assertTrue(client.rerank("key", "q", List.of(), 1).degraded());
    }

    @Test
    @DisplayName("HTTP 非 2xx：降级为原顺序")
    void shouldDegradeOnNon2xx() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(500, "{\"message\":\"boom\"}"));
            AiRerankClient.RerankOutcome outcome = client.rerank("key", "q", List.of("a", "b"), 2);
            assertTrue(outcome.degraded());
            assertEquals(2, outcome.docs().size());
            assertEquals(0, outcome.docs().get(0).index());
            assertEquals(1, outcome.docs().get(1).index());
        }
    }

    @Test
    @DisplayName("调用超时/网络异常：降级为原顺序")
    void shouldDegradeOnTimeout() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            HttpRequest request = mock(HttpRequest.class);
            when(request.header(anyString(), anyString())).thenReturn(request);
            when(request.timeout(anyInt())).thenReturn(request);
            when(request.body(anyString())).thenReturn(request);
            when(request.execute()).thenThrow(new RuntimeException("timeout"));
            httpUtil.when(() -> HttpUtil.createPost(anyString())).thenReturn(request);
            AiRerankClient.RerankOutcome outcome = client.rerank("key", "q", List.of("a"), 1);
            assertTrue(outcome.degraded());
        }
    }

    @Test
    @DisplayName("响应结构异常：降级为原顺序")
    void shouldDegradeOnMalformedBody() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, "{\"output\":{\"results\":\"oops\"}}"));
            AiRerankClient.RerankOutcome outcome = client.rerank("key", "q", List.of("a", "b"), 2);
            assertTrue(outcome.degraded());
            assertEquals(List.of(0, 1), outcome.docs().stream().map(AiRerankClient.RankedDoc::index).toList());
        }
    }

    @Test
    @DisplayName("下标越界的条目被丢弃")
    void shouldDropOutOfRangeIndex() {
        String body = "{\"output\":{\"results\":[{\"index\":9,\"relevance_score\":0.9},"
            + "{\"index\":0,\"relevance_score\":0.5}]}}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            AiRerankClient.RerankOutcome outcome = client.rerank("key", "q", List.of("a", "b"), 2);
            assertFalse(outcome.degraded());
            assertEquals(1, outcome.docs().size());
            assertEquals(0, outcome.docs().get(0).index());
        }
    }

}