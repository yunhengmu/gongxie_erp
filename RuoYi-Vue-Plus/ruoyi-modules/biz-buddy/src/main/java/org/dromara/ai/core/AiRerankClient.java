package org.dromara.ai.core;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本重排（Rerank）客户端：对「查询 + 候选切片文本」重新打分排序。
 *
 * <p><b>默认对接 DashScope 文本重排</b>（{@code POST /api/v1/services/rerank/text-rerank/text-rerank}，
 * 请求体 {@code {model, input:{query, documents:[...]}, parameters:{top_n, return_documents:false}}}，
 * 鉴权 {@code Authorization: Bearer <key>}）。端点 / 模型 / 超时均取自
 * {@link AiProperties.Rag}，可配置。</p>
 *
 * <p><b>健壮性</b>：未配密钥、HTTP 非 2xx、超时、响应结构不符时，<b>一律降级</b>——
 * 返回 {@link RerankOutcome#degraded()} 为 {@code true} 且 {@code docs} 为原始顺序，
 * 由调用方保持原顺序，绝不阻断检索。</p>
 *
 * <p>密钥来源已由调用方解密（复用知识库向量模型的密钥，见 {@code AiModelConfigService#decryptApiKey}），
 * 本类不做持久化、不打印密钥。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRerankClient {

    private final AiProperties aiProperties;

    /**
     * 文本重排。
     *
     * @param apiKey    明文密钥（为空直接降级）
     * @param query     查询文本
     * @param documents 候选文本列表（顺序即原始召回顺序）
     * @param topN      期望返回条数（超出候选数时取候选数）
     * @return 重排结果；任何异常均降级为「degraded=true + 原始顺序」
     */
    public RerankOutcome rerank(String apiKey, String query, List<String> documents, int topN) {
        int size = documents == null ? 0 : documents.size();
        if (StringUtils.isBlank(apiKey) || StringUtils.isBlank(query) || size == 0) {
            return RerankOutcome.degradedOrder(size);
        }

        AiProperties.Rag rag = aiProperties.getRag();
        String endpoint = StringUtils.trim(rag.getRerankEndpoint());
        String model = StringUtils.trim(rag.getRerankModel());
        if (StringUtils.isBlank(endpoint) || StringUtils.isBlank(model)) {
            log.warn("重排已启用但端点或模型未配置，降级为原顺序");
            return RerankOutcome.degradedOrder(size);
        }

        int effectiveTopN = topN > 0 ? Math.min(topN, size) : size;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", effectiveTopN);
        parameters.put("return_documents", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        int timeout = rag.getRerankTimeoutMs() > 0 ? rag.getRerankTimeoutMs() : 12000;
        int status;
        String responseBody;
        try (HttpResponse response = HttpUtil.createPost(endpoint)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(timeout)
            .body(JsonUtils.toJsonString(body))
            .execute()) {
            status = response.getStatus();
            responseBody = response.body();
        } catch (Exception e) {
            log.warn("重排调用失败（网络或超时），降级为原顺序：{}", e.getMessage());
            return RerankOutcome.degradedOrder(size);
        }
        if (status < 200 || status >= 300) {
            log.warn("重排返回非 2xx（HTTP {}），降级为原顺序：{}", status, abbreviate(responseBody));
            return RerankOutcome.degradedOrder(size);
        }
        List<RankedDoc> ranked = parseResults(responseBody, size);
        if (ranked.isEmpty()) {
            log.warn("重排响应结构异常或结果为空，降级为原顺序：{}", abbreviate(responseBody));
            return RerankOutcome.degradedOrder(size);
        }
        return new RerankOutcome(false, ranked);
    }

    /**
     * 解析重排响应：{@code output.results[]} 中每项含 {@code index}（原始下标）与 {@code relevance_score}。
     *
     * <p>返回前按下标去重、按分数降序排序；下标越界或为负的条目丢弃。</p>
     *
     * @param responseBody 响应体
     * @param size         候选数量
     * @return 排序后的条目；解析失败返回空列表
     */
    private List<RankedDoc> parseResults(String responseBody, int size) {
        List<RankedDoc> ranked = new ArrayList<>();
        try {
            JsonNode results = JsonUtils.getJsonMapper().readTree(responseBody).path("output").path("results");
            if (!results.isArray()) {
                return List.of();
            }
            boolean[] seen = new boolean[size];
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= size || seen[index]) {
                    continue;
                }
                seen[index] = true;
                ranked.add(new RankedDoc(index, item.path("relevance_score").asDouble(0D)));
            }
        } catch (Exception e) {
            return List.of();
        }
        ranked.sort(Comparator.comparingDouble(RankedDoc::score).reversed());
        return ranked;
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

    /**
     * 重排结果。
     *
     * @param degraded 是否降级（true 表示排序不可信，调用方应保持原顺序）
     * @param docs     按相关度降序的条目（降级时为原始顺序 0..n-1）
     */
    public record RerankOutcome(boolean degraded, List<RankedDoc> docs) {

        /**
         * 降级：返回原始顺序，调用方据此保持原序与原文分数。
         *
         * @param size 候选数量
         * @return 降级结果
         */
        public static RerankOutcome degradedOrder(int size) {
            List<RankedDoc> order = new ArrayList<>(Math.max(0, size));
            for (int i = 0; i < size; i++) {
                order.add(new RankedDoc(i, 0D));
            }
            return new RerankOutcome(true, order);
        }
    }

    /**
     * 单条重排条目。
     *
     * @param index 原始候选下标
     * @param score 重排相关度分（降级时无意义）
     */
    public record RankedDoc(int index, double score) {
    }

}