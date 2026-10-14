package org.dromara.ai.core.safety;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部内容安全引擎：把每段待审文本发给第三方内容安全 API，按其判定结果映射为
 * {@link ContentSafetyResult}（{@code engine=external} 时由 {@link AiSafetyGuard} 选用）。
 *
 * <p><b>契约</b>见 {@link AiProperties.External}：{@code POST <endpoint>}，请求体
 * {@code {"scene":"<场景编码>","text":"<待审文本>"}}，鉴权 {@code Authorization: Bearer <apiKey>}；
 * 期望响应 {@code {"hit":..., "hits":[...], "categories":[...]}}。密钥不落日志。</p>
 *
 * <p><b>配置不全</b>（未配地址或密钥）由 {@link #available} 判定，{@link AiSafetyGuard} 会据此
 * <b>降级为本地词库并告警</b>，故本类不会被以空配置调用；此处的空值兜底只为直接调用时返回未命中。</p>
 *
 * <p><b>调用失败降级（fail-open）</b>：HTTP 非 2xx / 超时 / 响应结构不符时，一律按未命中放行并告警——
 * 审核服务故障绝不能阻断对话主链路，与本地词库、重排模型的降级口径一致。</p>
 *
 * <p><b>调用代价</b>：本引擎按「一次 {@link AiSafetyGuard#guard} 一次外呼」工作。流式输出是逐片过审的，
 * 故开启 {@code engine=external} 且启用输出审核时，一轮回答会产生多次外呼（次数≈切分片数，可由
 * {@code holdBackChars} 调大以减少）；如需控本，可只启用「用户输入 / 渠道出站」审核。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalApiSafetyEngine implements IAiContentSafetyEngine {

    /**
     * 请求体字段：场景编码
     */
    private static final String FIELD_SCENE = "scene";

    /**
     * 请求体字段：待审文本
     */
    private static final String FIELD_TEXT = "text";

    private final AiProperties aiProperties;

    @Override
    public String engineName() {
        return "external";
    }

    @Override
    public boolean available(AiSecurityConfigBo config) {
        return config != null
            && StringUtils.isNotBlank(config.getProviderEndpoint())
            && StringUtils.isNotBlank(config.getProviderApiKey());
    }

    @Override
    public ContentSafetyResult check(String text, SafetyScene scene, AiSecurityConfigBo config) {
        if (StringUtils.isBlank(text)) {
            return ContentSafetyResult.miss();
        }
        String url = config == null ? "" : StringUtils.trim(config.getProviderEndpoint());
        String apiKey = config == null ? "" : StringUtils.trim(config.getProviderApiKey());
        if (StringUtils.isBlank(url) || StringUtils.isBlank(apiKey)) {
            // 配置不全已由 AiSafetyGuard 前置降级；直接调用时保持 fail-open
            return ContentSafetyResult.miss();
        }
        int timeout = aiProperties.getSecurity().getExternal().getTimeoutMs() > 0
            ? aiProperties.getSecurity().getExternal().getTimeoutMs() : 3000;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(FIELD_SCENE, scene.code());
        body.put(FIELD_TEXT, text);
        int status;
        String responseBody;
        try (HttpResponse response = HttpUtil.createPost(url)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(timeout)
            .body(JsonUtils.toJsonString(body))
            .execute()) {
            status = response.getStatus();
            responseBody = response.body();
        } catch (Exception e) {
            log.warn("外部审核引擎调用失败（网络或超时），按未命中放行：{}", e.getMessage());
            return ContentSafetyResult.miss();
        }
        if (status < 200 || status >= 300) {
            log.warn("外部审核引擎返回非 2xx（HTTP {}），按未命中放行：{}", status, abbreviate(responseBody));
            return ContentSafetyResult.miss();
        }
        return parseResult(responseBody);
    }

    /**
     * 解析外部引擎响应体（非法 JSON 按未命中放行）。
     *
     * @param responseBody 响应体
     * @return 校验结果
     */
    static ContentSafetyResult parseResult(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return ContentSafetyResult.miss();
        }
        try {
            return parseResult(JsonUtils.getJsonMapper().readTree(responseBody));
        } catch (Exception e) {
            log.warn("外部审核引擎响应结构不符，按未命中放行：{}", e.getMessage());
            return ContentSafetyResult.miss();
        }
    }

    /**
     * 解析已读取的响应节点：{@code hit} 为真时取 {@code hits} 与 {@code categories}。
     *
     * <p>包级可见以便单测（{@code HttpUtil} 与 {@code JsonUtils} 均为静态调用，无法注入替身）。</p>
     *
     * @param root 响应根节点
     * @return 校验结果
     */
    static ContentSafetyResult parseResult(JsonNode root) {
        if (root == null || !root.path("hit").asBoolean(false)) {
            return ContentSafetyResult.miss();
        }
        return new ContentSafetyResult(true, textList(root.path("hits")), textList(root.path("categories")));
    }

    /**
     * 取 JSON 数组中的字符串项（非数组或字段缺失时为空列表）。
     *
     * @param node JSON 节点
     * @return 字符串列表
     */
    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    /**
     * 截断过长响应文本，便于日志定位问题（不含密钥）。
     *
     * @param text 原文
     * @return 截断结果
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
    }

}
