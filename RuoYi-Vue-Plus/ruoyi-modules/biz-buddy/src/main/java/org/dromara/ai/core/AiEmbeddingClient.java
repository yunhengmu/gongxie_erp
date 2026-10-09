package org.dromara.ai.core;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI 兼容的文本向量（Embedding）客户端。
 *
 * <p>按批提交以降低调用次数；密钥由调用方解密后传入，本类不做持久化。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiEmbeddingClient {

    /**
     * 向量模型类型
     */
    private static final String TYPE_EMBEDDING = "embedding";

    /**
     * 端点后缀
     */
    private static final String ENDPOINT_SUFFIX = "/embeddings";

    private final AiProperties aiProperties;

    /**
     * 批量文本向量化。
     *
     * @param config  向量模型配置
     * @param apiKey  明文密钥（可为空，本地模型无需密钥）
     * @param texts   待向量化文本，顺序与返回结果一一对应
     * @return 向量列表，与入参顺序一致
     */
    public List<double[]> embed(AiModelConfig config, String apiKey, List<String> texts) {
        List<double[]> result = new ArrayList<>(texts.size());
        if (texts.isEmpty()) {
            return result;
        }
        validate(config);
        List<String> prepared = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (StringUtils.isBlank(text)) {
                throw new ServiceException("存在空文本切片，无法向量化，请先重新解析文档");
            }
            String trimmed = text.trim();
            int max = aiProperties.getEmbeddingMaxChars();
            prepared.add(trimmed.length() > max ? trimmed.substring(0, max) : trimmed);
        }

        int batchSize = Math.max(1, aiProperties.getEmbeddingBatchSize());
        for (int start = 0; start < prepared.size(); start += batchSize) {
            int end = Math.min(start + batchSize, prepared.size());
            result.addAll(embedBatch(config, apiKey, prepared.subList(start, end)));
        }
        return result;
    }

    /**
     * 单条文本向量化。
     *
     * @param config 向量模型配置
     * @param apiKey 明文密钥
     * @param text   待向量化文本
     * @return 向量
     */
    public double[] embedOne(AiModelConfig config, String apiKey, String text) {
        return embed(config, apiKey, List.of(text)).get(0);
    }

    /**
     * 提交一批文本并解析向量结果。
     *
     * @param config 向量模型配置
     * @param apiKey 明文密钥
     * @param batch  本批文本
     * @return 向量列表
     */
    private List<double[]> embedBatch(AiModelConfig config, String apiKey, List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModelCode());
        body.put("input", batch);
        // DashScope 支持显式指定维度，避免返回维度与知识库配置不一致
        if (isDashScopeHost(config.getBaseUrl()) && config.getDimension() != null) {
            body.put("dimensions", config.getDimension());
            body.put("encoding_format", "float");
        }

        int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : aiProperties.getTestTimeoutSeconds();
        HttpRequest request = HttpUtil.createPost(resolveEndpoint(config.getBaseUrl()))
            .header("Content-Type", "application/json")
            .timeout(timeout * 1000)
            .body(JsonUtils.toJsonString(body));
        if (StringUtils.isNotBlank(apiKey)) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        int status;
        String responseBody;
        try (HttpResponse response = request.execute()) {
            status = response.getStatus();
            responseBody = response.body();
        } catch (Exception e) {
            throw new ServiceException("向量化调用失败：" + e.getMessage());
        }
        if (status < 200 || status >= 300) {
            throw new ServiceException("向量化失败（HTTP " + status + "）：" + extractError(responseBody));
        }
        return parseVectors(responseBody, batch.size());
    }

    /**
     * 解析向量响应，按 index 排序保证顺序与入参一致。
     *
     * @param responseBody 响应体
     * @param expected     期望条数
     * @return 向量列表
     */
    private List<double[]> parseVectors(String responseBody, int expected) {
        JsonNode data = JsonUtils.getJsonMapper().readTree(responseBody).path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new ServiceException("向量化返回条数与请求不一致（期望 " + expected
                + "，实际 " + (data.isArray() ? data.size() : 0) + "）：" + abbreviate(responseBody));
        }
        double[][] ordered = new double[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode embedding = item.path("embedding");
            if (index < 0 || index >= expected || !embedding.isArray() || embedding.size() == 0) {
                throw new ServiceException("向量化返回结构异常：" + abbreviate(responseBody));
            }
            double[] vector = new double[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.get(i).asDouble();
            }
            ordered[index] = vector;
        }
        List<double[]> result = new ArrayList<>(expected);
        for (double[] vector : ordered) {
            if (vector == null) {
                throw new ServiceException("向量化返回缺少部分结果：" + abbreviate(responseBody));
            }
            result.add(vector);
        }
        return result;
    }

    /**
     * 校验模型配置可用于向量化。
     *
     * @param config 模型配置
     */
    private void validate(AiModelConfig config) {
        if (config == null) {
            throw new ServiceException("向量模型不存在或已被删除");
        }
        if (!TYPE_EMBEDDING.equalsIgnoreCase(StringUtils.trim(config.getModelType()))) {
            throw new ServiceException("所选模型不是向量模型：" + config.getConfigName());
        }
        if (StringUtils.isBlank(config.getBaseUrl()) || StringUtils.isBlank(config.getModelCode())) {
            throw new ServiceException("向量模型的服务地址或模型标识为空，请先补全配置");
        }
        if (config.getBaseUrl().contains("{") || config.getBaseUrl().contains("}")) {
            throw new ServiceException("向量模型服务地址仍包含占位符，请替换为真实值");
        }
    }

    /**
     * 拼接 OpenAI 兼容端点地址。
     *
     * @param baseUrl 服务地址
     * @return 完整端点
     */
    private String resolveEndpoint(String baseUrl) {
        String base = StringUtils.trim(baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith(ENDPOINT_SUFFIX) ? base : base + ENDPOINT_SUFFIX;
    }

    /**
     * 是否 DashScope（含新版百炼 maas 域名）地址。
     *
     * @param baseUrl 服务地址
     * @return 是否 DashScope
     */
    private boolean isDashScopeHost(String baseUrl) {
        String base = StringUtils.trim(baseUrl).toLowerCase(Locale.ROOT);
        return base.contains("dashscope.aliyuncs.com") || base.contains(".maas.aliyuncs.com");
    }

    /**
     * 提取错误信息。
     *
     * @param responseBody 响应体
     * @return 错误说明
     */
    private String extractError(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "服务端未返回内容";
        }
        try {
            JsonNode node = JsonUtils.getJsonMapper().readTree(responseBody);
            String message = node.path("error").path("message").asText("");
            if (StringUtils.isBlank(message)) {
                message = node.path("message").asText("");
            }
            if (StringUtils.isNotBlank(message)) {
                return abbreviate(message);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，截断原文返回
        }
        return abbreviate(responseBody);
    }

    /**
     * 截断过长文本。
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