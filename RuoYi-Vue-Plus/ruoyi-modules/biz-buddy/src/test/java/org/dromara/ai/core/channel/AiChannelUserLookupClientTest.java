package org.dromara.ai.core.channel;

import com.sun.net.httpserver.HttpServer;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiChannelUserLookupClient} 单元测试：用本地假平台验证钉钉 / 飞书的取 token 与查询解析。
 */
@DisplayName("渠道外部用户标识查询客户端")
class AiChannelUserLookupClientTest {

    private HttpServer server;

    private int port;

    /** 钉钉 getbymobile 的假响应体 */
    private final AtomicReference<String> dingTalkResult = new AtomicReference<>();

    /** 飞书 batch_get_id 的假响应体 */
    private final AtomicReference<String> feishuResult = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        dingTalkResult.set("{\"errcode\":0,\"errmsg\":\"ok\",\"result\":{\"userid\":\"staff-1\",\"name\":\"张三\"}}");
        feishuResult.set("{\"code\":0,\"msg\":\"ok\",\"data\":{\"user_list\":[]}}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.contains("/gettoken")) {
                body = "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"dt-token\"}";
            } else if (path.contains("getbymobile")) {
                body = dingTalkResult.get();
            } else if (path.contains("tenant_access_token")) {
                body = "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"fs-token\",\"expire\":7200}";
            } else if (path.contains("batch_get_id")) {
                body = feishuResult.get();
            } else {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    @DisplayName("支持范围：仅钉钉与飞书")
    void shouldReportSupportedPlatforms() {
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        assertTrue(client.supports(AiChannel.PLATFORM_DINGTALK));
        assertTrue(client.supports(AiChannel.PLATFORM_FEISHU));
        assertFalse(client.supports(AiChannel.PLATFORM_WECOM));
        assertFalse(client.supports(AiChannel.PLATFORM_GITHUB));
    }

    @Test
    @DisplayName("钉钉：按手机号命中，返回 userid 与用户名")
    void shouldLookupDingTalk() {
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        Optional<AiChannelUserLookupClient.LookupResult> result =
            client.lookup(AiChannel.PLATFORM_DINGTALK, base(), "appkey", "appsecret", "13800000000");

        assertTrue(result.isPresent());
        assertEquals("staff-1", result.get().externalUserId());
        assertEquals("张三", result.get().displayName());
    }

    @Test
    @DisplayName("钉钉：平台 errcode 非 0 抛业务异常并带上平台错误码")
    void shouldFailDingTalkOnErrcode() {
        dingTalkResult.set("{\"errcode\":60011,\"errmsg\":\"没有调用该接口的权限\"}");
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        ServiceException exception = assertThrows(ServiceException.class,
            () -> client.lookup(AiChannel.PLATFORM_DINGTALK, base(), "appkey", "appsecret", "13800000000"));

        assertTrue(exception.getMessage().contains("60011"));
    }

    @Test
    @DisplayName("钉钉：result 缺 userid 时视为未找到")
    void shouldReturnEmptyWhenDingTalkUserMissing() {
        dingTalkResult.set("{\"errcode\":0,\"errmsg\":\"ok\",\"result\":{}}");
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        assertTrue(client.lookup(AiChannel.PLATFORM_DINGTALK, base(), "appkey", "appsecret", "13800000000").isEmpty());
    }

    @Test
    @DisplayName("飞书：按手机号命中，返回 open_id")
    void shouldLookupFeishu() {
        feishuResult.set("{\"code\":0,\"msg\":\"ok\",\"data\":{\"user_list\":"
            + "[{\"mobile\":\"+8613800000000\",\"user_id\":\"ou_1\"}]}}");
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        Optional<AiChannelUserLookupClient.LookupResult> result =
            client.lookup(AiChannel.PLATFORM_FEISHU, base(), "cli_x", "secret", "13800000000");

        assertTrue(result.isPresent());
        assertEquals("ou_1", result.get().externalUserId());
    }

    @Test
    @DisplayName("飞书：user_id 为空时视为未找到")
    void shouldReturnEmptyWhenFeishuUserMissing() {
        feishuResult.set("{\"code\":0,\"msg\":\"ok\",\"data\":{\"user_list\":[{\"user_id\":\"\"}]}}");
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        assertTrue(client.lookup(AiChannel.PLATFORM_FEISHU, base(), "cli_x", "secret", "13800000000").isEmpty());
    }

    @Test
    @DisplayName("飞书：平台 code 非 0 抛业务异常并带上平台错误码")
    void shouldFailFeishuOnCode() {
        feishuResult.set("{\"code\":99991672,\"msg\":\"scope 未申请\"}");
        AiChannelUserLookupClient client = new AiChannelUserLookupClient();

        ServiceException exception = assertThrows(ServiceException.class,
            () -> client.lookup(AiChannel.PLATFORM_FEISHU, base(), "cli_x", "secret", "13800000000"));

        assertTrue(exception.getMessage().contains("99991672"));
    }

}
