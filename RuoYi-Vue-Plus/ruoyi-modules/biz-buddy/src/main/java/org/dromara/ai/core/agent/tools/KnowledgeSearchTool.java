package org.dromara.ai.core.agent.tools;

import org.dromara.ai.util.AiUntrustedContent;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具（只读）。
 *
 * <p>每个实例绑定一个智能体的知识库集合：智能体未绑定知识库时不检索，直接返回说明。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class KnowledgeSearchTool extends ToolBase {

    /**
     * 工具编码（与 ai_tool 注册表中的 tool_code 保持一致）
     */
    public static final String TOOL_NAME = "search_knowledge_base";

    /**
     * 工具描述
     */
    private static final String DESCRIPTION =
        "在企业知识库中检索与用户问题相关的资料片段。当你需要依据企业内部资料回答问题时调用。";

    /**
     * 默认召回条数
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 检索结果回填给模型的最大字符数
     */
    private static final int MAX_RESULT_CHARS = 6000;

    private final IAiVectorService vectorService;

    private final List<Long> kbIds;

    private final Integer defaultTopK;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    /**
     * 是否平台出口（MCP）模式：绑定集合由服务端按「公开且启用」解析完毕，不再按提问用户过滤
     */
    private final boolean platformScope;

    /**
     * 智能体场景构造：知识库绑定来自智能体定义，调用时按提问用户过滤生效范围。
     *
     * @param vectorService        向量检索服务
     * @param kbIds                智能体绑定的知识库主键集合
     * @param defaultTopK          默认召回条数，留空取 5
     * @param knowledgeBaseService 知识库服务（用于按提问用户校验生效范围）
     */
    public KnowledgeSearchTool(IAiVectorService vectorService, List<Long> kbIds, Integer defaultTopK,
                               IAiKnowledgeBaseService knowledgeBaseService) {
        this(vectorService, kbIds, defaultTopK, knowledgeBaseService, false);
    }

    /**
     * 平台出口（MCP）构造（REQ-AI-15）。
     *
     * <p>MCP 出口的调用方只携带平台口令、没有平台用户，故不按用户过滤生效范围；
     * 越权风险由「绑定集合只取公开且启用的知识库」在上游收敛（见
     * {@link IAiKnowledgeBaseService#listPublicEnabledKbIds()}）。</p>
     *
     * @param vectorService        向量检索服务
     * @param kbIds                平台公开且启用的知识库主键集合
     * @param defaultTopK          默认召回条数，留空取 5
     * @param knowledgeBaseService 知识库服务
     * @param platformScope        是否平台出口模式
     */
    public KnowledgeSearchTool(IAiVectorService vectorService, List<Long> kbIds, Integer defaultTopK,
                               IAiKnowledgeBaseService knowledgeBaseService, boolean platformScope) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.vectorService = vectorService;
        this.kbIds = kbIds == null ? List.of() : kbIds;
        this.defaultTopK = defaultTopK;
        this.knowledgeBaseService = knowledgeBaseService;
        this.platformScope = platformScope;
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "检索关键词或问题"));
        properties.put("top_k", Map.of("type", "integer", "description", "召回条数，默认 5"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();
        if (kbIds.isEmpty()) {
            return Mono.just(ToolResultBlock.text("该智能体未绑定知识库，无法检索。").withIdAndName(id, name));
        }
        Map<String, Object> input = param == null || param.getInput() == null ? Map.of() : param.getInput();
        String query = StringUtils.trim(String.valueOf(input.getOrDefault("query", "")));
        if (StringUtils.isBlank(query)) {
            return Mono.just(ToolResultBlock.error("检索关键词不能为空").withIdAndName(id, name));
        }
        // 生效范围（REQ-AI-17）：Agent 实例按智能体缓存（非按用户），故在此按提问用户实时过滤；
        // 过滤口径为「可见（visible_scope）**且启用**」——停用库同样不应被检索（docs/14 第 36 项）。
        // 平台出口（MCP）无提问用户，绑定集合已由服务端按「公开且启用」解析，不再过滤（REQ-AI-15）
        List<Long> accessible = platformScope
            ? kbIds
            : knowledgeBaseService.filterRetrievableKbIds(kbIds, resolveOperatorId(param));
        if (accessible.isEmpty()) {
            String message = platformScope
                ? "未检索到相关内容：平台当前没有可对外检索的公开知识库。"
                : "未检索到相关内容：该智能体绑定的知识库均不在你的生效范围内，请联系知识库创建人调整「生效范围」。";
            return Mono.just(ToolResultBlock.text(message).withIdAndName(id, name));
        }
        int topK = resolveTopK(input.get("top_k"));
        try {
            List<AiRetrievalHitVo> hits = new ArrayList<>();
            for (Long kbId : accessible) {
                List<AiRetrievalHitVo> part = vectorService.retrieve(kbId, query, topK, null);
                if (part != null) {
                    hits.addAll(part);
                }
            }
            hits.sort(Comparator.comparing(AiRetrievalHitVo::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
            if (hits.size() > topK) {
                hits = hits.subList(0, topK);
            }
            if (hits.isEmpty()) {
                return Mono.just(ToolResultBlock.text("未检索到相关内容。").withIdAndName(id, name));
            }
            return Mono.just(ToolResultBlock.text(AiUntrustedContent.wrap(render(hits))).withIdAndName(id, name));
        } catch (ServiceException e) {
            // 检索失败不打断整轮对话，交由模型说明情况
            return Mono.just(ToolResultBlock.error(e.getMessage()).withIdAndName(id, name));
        } catch (Exception e) {
            log.error("知识库检索工具执行异常，kbIds={}", kbIds, e);
            return Mono.just(ToolResultBlock.error("知识库检索失败：" + e.getMessage()).withIdAndName(id, name));
        }
    }

    /**
     * 从运行时上下文解析提问人。
     *
     * <p>工具运行在 Reactor 线程，Sa-Token 的线程上下文不可用，故操作人取自
     * {@link ToolCallParam#getRuntimeContext()}。</p>
     *
     * @param param 工具调用参数
     * @return 提问人用户 ID；无法解析时返回 null
     */
    private Long resolveOperatorId(ToolCallParam param) {
        String raw = param == null || param.getRuntimeContext() == null
            ? null : param.getRuntimeContext().getUserId();
        String value = StringUtils.trim(raw);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析召回条数。
     *
     * @param raw 模型传入的 top_k
     * @return 生效的召回条数
     */
    private int resolveTopK(Object raw) {
        int topK = defaultTopK == null ? DEFAULT_TOP_K : defaultTopK;
        if (raw instanceof Number number && number.intValue() > 0) {
            topK = number.intValue();
        } else if (raw != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(raw).trim());
                if (parsed > 0) {
                    topK = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法入参直接回退默认值
            }
        }
        return Math.min(topK, 50);
    }

    /**
     * 渲染检索结果文本（总长截断）。
     *
     * @param hits 命中切片
     * @return 结果文本
     */
    private String render(List<AiRetrievalHitVo> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库检索结果】共 ").append(hits.size()).append(" 条\n");
        int index = 1;
        for (AiRetrievalHitVo hit : hits) {
            sb.append("\n[").append(index++).append("] 文档：")
                .append(StringUtils.isBlank(hit.getDocName()) ? "未知文档" : hit.getDocName())
                .append(" ｜ 切片 #").append(hit.getChunkIndex() == null ? "-" : hit.getChunkIndex())
                .append(" ｜ 相似度 ").append(hit.getScore() == null ? "-" : String.format("%.4f", hit.getScore()))
                .append("\n").append(StringUtils.trim(hit.getContent())).append("\n");
            if (sb.length() > MAX_RESULT_CHARS) {
                break;
            }
        }
        return sb.length() > MAX_RESULT_CHARS ? sb.substring(0, MAX_RESULT_CHARS) : sb.toString();
    }

}
