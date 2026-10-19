package org.dromara.ai.core.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.feishu.FeishuAccessTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiChannel;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按手机号查询外部平台用户标识（渠道管理面的辅助工具）。
 *
 * <p>「渠道接入」要求外部消息先绑定「外部用户标识 → 平台账号」，而标识（钉钉 UserID、飞书 open_id）
 * 在平台界面上不直接可见，故提供本查询：拿渠道里已存的应用凭据调平台通讯录接口，把手机号换成该标识。</p>
 *
 * <p><b>支持范围</b>：能用渠道已存凭据调通平台的才支持——钉钉（AppKey/AppSecret 换 token 后调
 * {@code topapi/v2/user/getbymobile}）与飞书（tenant_access_token 调 {@code contact/v3/users/batch_get_id}）；
 * 企业微信的 {@code cgi-bin/user/getuserid} 需要「通讯录同步」独立 secret、GitHub 无手机号概念，两者不支持。</p>
 *
 * <p>本类只负责：域名派生 + 取 token + 组请求 + 解析响应；**不碰数据库 / 事务 / 加解密**，
 * 便于在单测中以替身替换。日志不打印 appSecret 与 token。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiChannelUserLookupClient {

    /**
     * 钉钉新版 OpenAPI 基址（渠道 apiBase 默认值）
     */
    private static final String DINGTALK_API_BASE = "https://api.dingtalk.com";

    /**
     * 钉钉旧版 OpenAPI 基址（topapi 与 gettoken 走这里）
     */
    private static final String DINGTALK_OAPI_BASE = "https://oapi.dingtalk.com";

    /**
     * 飞书开放平台默认基址
     */
    private static final String FEISHU_DEFAULT_API_BASE = "https://open.feishu.cn";

    private static final long TOKEN_TIMEOUT_SECONDS = 10L;

    private static final long CALL_TIMEOUT_SECONDS = 12L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 支持按手机号查外部用户标识的平台
     */
    private static final Set<String> SUPPORTED_PLATFORMS =
        Set.of(AiChannel.PLATFORM_DINGTALK, AiChannel.PLATFORM_FEISHU);

    /**
     * 该平台是否支持按手机号查询外部用户标识。
     *
     * @param platformType 平台类型
     * @return true 表示支持
     */
    public boolean supports(String platformType) {
        return SUPPORTED_PLATFORMS.contains(platformType);
    }

    /**
     * 按手机号查询外部用户标识。
     *
     * @param platformType 平台类型（仅钉钉 / 飞书）
     * @param apiBase      渠道配置的 API 基址（可空）
     * @param appId        应用标识（钉钉 AppKey / 飞书 appId）
     * @param appSecret    应用密钥（明文）
     * @param mobile       11 位手机号
     * @return 命中的标识；平台未找到时返回 {@code Optional.empty()}
     */
    public Optional<LookupResult> lookup(String platformType, String apiBase, String appId,
                                         String appSecret, String mobile) {
        return switch (platformType) {
            case AiChannel.PLATFORM_DINGTALK -> lookupDingTalk(apiBase, appId, appSecret, mobile);
            case AiChannel.PLATFORM_FEISHU -> lookupFeishu(apiBase, appId, appSecret, mobile);
            default -> Optional.empty();
        };
    }

    /**
     * 钉钉：{@code oapi.dingtalk.com/gettoken} 取旧版 access_token，再调
     * {@code topapi/v2/user/getbymobile} 按手机号换 userid。
     */
    private Optional<LookupResult> lookupDingTalk(String apiBase, String appId, String appSecret, String mobile) {
        String oapiBase = resolveDingTalkOapiBase(apiBase);
        String tokenBody;
        try {
            tokenBody = WebClient.builder().build().get()
                .uri(oapiBase + "/gettoken?appkey={appkey}&appsecret={appsecret}", appId, appSecret)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(TOKEN_TIMEOUT_SECONDS));
        } catch (Exception e) {
            throw new ServiceException("调用钉钉获取访问令牌失败：" + e.getMessage());
        }
        JsonNode tokenRoot = readTree(tokenBody);
        int tokenErrCode = tokenRoot.path("errcode").asInt(-1);
        String token = tokenRoot.path("access_token").asText(null);
        if (StringUtils.isBlank(token)) {
            throw new ServiceException("钉钉未返回 access_token（errcode=" + tokenErrCode + "，"
                + tokenRoot.path("errmsg").asText("未知错误") + "）");
        }

        String body;
        try {
            body = WebClient.builder().build().post()
                .uri(oapiBase + "/topapi/v2/user/getbymobile?access_token={token}", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mobile", mobile))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(CALL_TIMEOUT_SECONDS));
        } catch (Exception e) {
            throw new ServiceException("调用钉钉用户查询接口失败：" + e.getMessage());
        }
        JsonNode root = readTree(body);
        int errcode = root.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new ServiceException("钉钉接口报错（errcode=" + errcode + "）："
                + root.path("errmsg").asText("未知错误"));
        }
        JsonNode result = root.path("result");
        String userId = result.path("userid").asText(null);
        if (StringUtils.isBlank(userId)) {
            return Optional.empty();
        }
        return Optional.of(new LookupResult(userId, result.path("name").asText(null)));
    }

    /**
     * 飞书：tenant_access_token 调 {@code contact/v3/users/batch_get_id} 按手机号换 open_id。
     */
    private Optional<LookupResult> lookupFeishu(String apiBase, String appId, String appSecret, String mobile) {
        String base = StringUtils.isBlank(StringUtils.trim(apiBase)) ? FEISHU_DEFAULT_API_BASE : apiBase;
        String token;
        try {
            token = new FeishuAccessTokenProvider(base, appId, appSecret)
                .token().block(Duration.ofSeconds(TOKEN_TIMEOUT_SECONDS));
        } catch (Exception e) {
            throw new ServiceException("调用飞书获取 tenant_access_token 失败：" + e.getMessage());
        }
        if (StringUtils.isBlank(token)) {
            throw new ServiceException("飞书未返回 tenant_access_token（请检查 appId / appSecret）");
        }

        String body;
        try {
            body = WebClient.builder().build().post()
                .uri(base + "/open-apis/contact/v3/users/batch_get_id?user_id_type=open_id")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mobiles", List.of("+86" + mobile)))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(CALL_TIMEOUT_SECONDS));
        } catch (Exception e) {
            throw new ServiceException("调用飞书用户查询接口失败：" + e.getMessage());
        }
        JsonNode root = readTree(body);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new ServiceException("飞书接口报错（code=" + code + "）："
                + root.path("msg").asText("未知错误"));
        }
        JsonNode userList = root.path("data").path("user_list");
        if (userList.isArray()) {
            for (JsonNode user : userList) {
                String userId = user.path("user_id").asText(null);
                if (StringUtils.isNotBlank(userId)) {
                    return Optional.of(new LookupResult(userId, null));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 派生钉钉 topapi / gettoken 的基址：渠道 apiBase 为空或其指向新版 OpenAPI 时用官方旧版基址，
     * 其余（内网代理 / 自建网关）按原值复用。
     */
    private String resolveDingTalkOapiBase(String apiBase) {
        String base = StringUtils.trim(apiBase);
        if (StringUtils.isBlank(base) || base.contains(DINGTALK_API_BASE)) {
            return DINGTALK_OAPI_BASE;
        }
        return base;
    }

    /**
     * 安全解析 JSON：报文为空或非法时不抛异常，返回空对象供 {@code path()} 兜底。
     */
    private JsonNode readTree(String body) {
        if (StringUtils.isBlank(body)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("渠道用户标识查询：平台返回报文解析失败");
            return MAPPER.createObjectNode();
        }
    }

    /**
     * 查询结果：外部用户标识 + 展示名（平台未提供时为 null）。
     *
     * @param externalUserId 外部用户标识（钉钉 userid / 飞书 open_id）
     * @param displayName    平台返回的用户名（可空）
     */
    public record LookupResult(String externalUserId, String displayName) {
    }

}
