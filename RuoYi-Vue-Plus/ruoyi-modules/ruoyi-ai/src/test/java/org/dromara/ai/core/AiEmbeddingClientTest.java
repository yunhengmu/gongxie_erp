package org.dromara.ai.core;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiEmbeddingClient} 单元测试：请求构造、响应解析与异常分支（HTTP 全部打桩，不发真实请求）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OpenAI 兼容向量化客户端")
class AiEmbeddingClientTest {

    private AiProperties properties;
    private AiEmbeddingClient client;

    @BeforeAll
    static void beforeAll() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        client = new AiEmbeddingClient(properties);
    }

    private AiModelConfig config(String baseUrl) {
        AiModelConfig config = new AiModelConfig();
        config.setModelType("embedding");
        config.setModelCode("text-embedding-v3");
        config.setBaseUrl(baseUrl);
        config.setConfigName("测试向量模型");
        return config;
    }

    private HttpResponse response(int status, String body) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private HttpRequest chainableRequest() {
        HttpRequest request = mock(HttpRequest.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyInt())).thenReturn(request);
        when(request.body(anyString())).thenReturn(request);
        return request;
    }

    private HttpRequest requestWith(MockedStatic<HttpUtil> httpUtil, HttpResponse response) {
        HttpRequest request = chainableRequest();
        when(request.execute()).thenReturn(response);
        httpUtil.when(() -> HttpUtil.createPost(anyString())).thenReturn(request);
        return request;
    }

    @Test
    @DisplayName("空文本列表直接返回空结果")
    void shouldReturnEmptyForEmptyInput() {
        assertTrue(client.embed(config("https://api.test/v1"), "key", List.of()).isEmpty());
    }

    @Test
    @DisplayName("模型配置非法时拒绝向量化")
    void shouldRejectInvalidConfig() {
        assertThrows(ServiceException.class, () -> client.embed(null, "key", List.of("a")));

        AiModelConfig chat = config("https://api.test/v1");
        chat.setModelType("chat");
        assertThrows(ServiceException.class, () -> client.embed(chat, "key", List.of("a")));

        AiModelConfig blank = config("https://api.test/v1");
        blank.setBaseUrl("  ");
        assertThrows(ServiceException.class, () -> client.embed(blank, "key", List.of("a")));

        AiModelConfig placeholder = config("https://{host}/v1");
        assertThrows(ServiceException.class, () -> client.embed(placeholder, "key", List.of("a")));
    }

    @Test
    @DisplayName("存在空白文本切片时抛出异常")
    void shouldRejectBlankText() {
        ServiceException ex = assertThrows(ServiceException.class,
            () -> client.embed(config("https://api.test/v1"), "key", List.of("  ")));
        assertTrue(ex.getMessage().contains("空文本切片"));
    }

    @Test
    @DisplayName("正常解析响应并按 index 还原顺序")
    void shouldParseVectorsInOrder() {
        String body = "{\"data\":[{\"index\":1,\"embedding\":[0.3,0.4,0.5]},"
            + "{\"index\":0,\"embedding\":[0.1,0.2]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            List<double[]> result = client.embed(config("https://api.test/v1/"), "key", List.of(" a ", "b"));
            assertEquals(2, result.size());
            assertArrayEquals(new double[]{0.1, 0.2}, result.get(0));
            assertArrayEquals(new double[]{0.3, 0.4, 0.5}, result.get(1));
        }
    }

    @Test
    @DisplayName("单条向量化返回首个向量")
    void shouldEmbedOne() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            assertArrayEquals(new double[]{1.0, 2.0}, client.embedOne(config("https://api.test/v1"), null, "x"));
        }
    }

    @Test
    @DisplayName("端点已含 /embeddings 时不重复拼接")
    void shouldNotDuplicateEndpoint() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            client.embed(config("https://api.test/v1/embeddings"), "key", List.of("a"));
            httpUtil.verify(() -> HttpUtil.createPost("https://api.test/v1/embeddings"), times(1));
        }
    }

    @Test
    @DisplayName("超长文本按配置截断后再提交")
    void shouldTruncateLongText() {
        properties.setEmbeddingMaxChars(3);
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            HttpRequest request = requestWith(httpUtil, response(200, body));
            client.embed(config("https://api.test/v1"), "key", List.of("abcdef"));
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(request).body(captor.capture());
            assertTrue(captor.getValue().contains("\"abc\""));
            assertTrue(!captor.getValue().contains("abcdef"));
        }
    }

    @Test
    @DisplayName("批量大小生效：分批提交")
    void shouldBatchRequests() {
        properties.setEmbeddingBatchSize(1);
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            List<double[]> result = client.embed(config("https://api.test/v1"), "key", List.of("a", "b"));
            assertEquals(2, result.size());
            httpUtil.verify(() -> HttpUtil.createPost(anyString()), times(2));
        }
    }

    @Test
    @DisplayName("DashScope 地址附带维度与编码格式")
    void shouldSendDimensionForDashScope() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        AiModelConfig config = config("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setDimension(1024);
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            HttpRequest request = requestWith(httpUtil, response(200, body));
            client.embed(config, "key", List.of("a"));
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(request).body(captor.capture());
            assertTrue(captor.getValue().contains("\"dimensions\":1024"));
            assertTrue(captor.getValue().contains("encoding_format"));
        }
    }

    @Test
    @DisplayName("非 2xx 响应携带服务端错误信息抛出异常")
    void shouldThrowForNon2xx() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(500, "{\"error\":{\"message\":\"boom\"}}"));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("HTTP 500"));
            assertTrue(ex.getMessage().contains("boom"));
        }
    }

    @Test
    @DisplayName("非 2xx 且响应体为空时给出默认提示")
    void shouldHandleBlankErrorBody() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(503, " "));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("服务端未返回内容"));
        }
    }

    @Test
    @DisplayName("非 JSON 错误响应截断原文返回")
    void shouldFallbackToRawBody() {
        String raw = "x".repeat(400);
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(400, raw));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("..."));
        }
    }

    @Test
    @DisplayName("返回条数与请求不一致时抛出异常")
    void shouldRejectCountMismatch() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]},{\"index\":1,\"embedding\":[0.2]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("条数与请求不一致"));
        }
    }

    @Test
    @DisplayName("返回结构异常（index 越界）时抛出异常")
    void shouldRejectInvalidIndex() {
        String body = "{\"data\":[{\"index\":-1,\"embedding\":[0.1]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("结构异常"));
        }
    }

    @Test
    @DisplayName("返回缺少部分结果时抛出异常")
    void shouldRejectMissingResult() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]},{\"index\":0,\"embedding\":[0.2]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            requestWith(httpUtil, response(200, body));
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a", "b")));
            assertTrue(ex.getMessage().contains("缺少部分结果"));
        }
    }

    @Test
    @DisplayName("执行 HTTP 请求抛异常时转换为 ServiceException")
    void shouldWrapExecuteFailure() {
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            HttpRequest request = chainableRequest();
            when(request.execute()).thenThrow(new RuntimeException("net-down"));
            httpUtil.when(() -> HttpUtil.createPost(anyString())).thenReturn(request);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> client.embed(config("https://api.test/v1"), "key", List.of("a")));
            assertTrue(ex.getMessage().contains("向量化调用失败"));
        }
    }

    @Test
    @DisplayName("无密钥时不携带 Authorization 头")
    void shouldSkipAuthorizationWhenNoKey() {
        String body = "{\"data\":[{\"index\":0,\"embedding\":[0.1]}]}";
        try (MockedStatic<HttpUtil> httpUtil = mockStatic(HttpUtil.class)) {
            HttpRequest request = requestWith(httpUtil, response(200, body));
            client.embed(config("https://api.test/v1"), null, List.of("a"));
            verify(request, org.mockito.Mockito.never()).header(eq("Authorization"), anyString());
        }
    }

}