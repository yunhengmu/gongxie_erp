package org.dromara.ai.core.agent.tools;

import org.dromara.ai.core.AiRetrievalContextAssembler;
import org.dromara.ai.core.AiRetrievalSourceRegistry;
import org.dromara.ai.util.AiUntrustedContent;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.vo.AiRetrievalHitVo;
import org.dromara.ai.domain.vo.AiSourceVo;
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
     * 检索结果回填给模型的最大字符数（未注入装配配置时的兜底值）
     */
    private static final int DEFAULT_MAX_RESULT_CHARS = 6000;

    private final IAiVectorService vectorService;

    private final List<Long> kbIds;

    private final Integer defaultTopK;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    /**
     * 是否平台出口（MCP）模式：绑定集合由服务端按「公开且启用」解析完毕，不再按提问用户过滤
     */
    private final boolean platformScope;

    /**
     * 上下文装配参数（P0-③ 去重 + 相邻合并 + 字符上限），来自平台配置；为 null 时取默认值
     */
    private final AiRetrievalContextAssembler.Options assembleOptions;

    /**
     * 检索来源注册表：命中时把结构化来源按 toolCallId 暂存，供事件桥下发「参考来源」（可空）
     */
    private final AiRetrievalSourceRegistry sourceRegistry;

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
        this(vectorService, kbIds, defaultTopK, knowledgeBaseService, false, null, null);
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
        this(vectorService, kbIds, defaultTopK, knowledgeBaseService, platformScope, null, null);
    }

    /**
     * 完整构造（含上下文装配参数，P0-③）。
     *
     * @param vectorService        向量检索服务
     * @param kbIds                知识库主键集合
     * @param defaultTopK          默认召回条数，留空取 5
     * @param knowledgeBaseService 知识库服务
     * @param platformScope        是否平台出口模式
     * @param assembleOptions      上下文装配参数；为 null 取默认值
     */
    public KnowledgeSearchTool(IAiVectorService vectorService, List<Long> kbIds, Integer defaultTopK,
                               IAiKnowledgeBaseService knowledgeBaseService, boolean platformScope,
                               AiRetrievalContextAssembler.Options assembleOptions) {
        this(vectorService, kbIds, defaultTopK, knowledgeBaseService, platformScope, assembleOptions, null);
    }

    /**
     * 完整构造（含上下文装配参数与来源注册表）。
     *
     * <p><b>是否重排由知识库配置决定</b>（{@code ai_knowledge_base.rerank_enabled / rerank_model_id}，
     * 2026-10-14 起不再由本工具按平台开关写死）。</p>
     *
     * @param vectorService        向量检索服务
     * @param kbIds                知识库主键集合
     * @param defaultTopK          默认召回条数，留空取 5
     * @param knowledgeBaseService 知识库服务
     * @param platformScope        是否平台出口模式
     * @param assembleOptions      上下文装配参数；为 null 取默认值
     * @param sourceRegistry       检索来源注册表；为 null 时不暂存来源
     */
    public KnowledgeSearchTool(IAiVectorService vectorService, List<Long> kbIds, Integer defaultTopK,
                               IAiKnowledgeBaseService knowledgeBaseService, boolean platformScope,
                               AiRetrievalContextAssembler.Options assembleOptions,
                               AiRetrievalSourceRegistry sourceRegistry) {
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
        this.assembleOptions = assembleOptions;
        this.sourceRegistry = sourceRegistry;
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
                // 传三个 null＝(阈值默认, 重排按知识库配置, 融合模式默认)
                List<AiRetrievalHitVo> part = vectorService.retrieve(kbId, query, topK, null, null, null);
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
            // 命中非空：把结构化来源按 toolCallId 暂存，供事件桥下发「参考来源」（渲染文本与安全包裹语义不变）
            if (sourceRegistry != null) {
                sourceRegistry.put(id, toSources(hits));
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
     * 渲染检索结果文本。
     *
     * <p>先经上下文装配（P0-③ 去重 + 相邻合并 + 字符上限，P0-④ 可配 Token 预算），再逐条渲染出处行：
     * 文档名 + 章节路径 + 页码 + 切片号 + 相似度（章节 / 页码为空则跳过该段）；
     * 末尾仍以**生效字符上限**（min(绝对字符上限, Token 预算折算值)）做一次安全截断。编号与安全包裹保持原样。</p>
     *
     * @param hits 命中切片（已按相关度降序）
     * @return 结果文本
     */
    private String render(List<AiRetrievalHitVo> hits) {
        int maxChars = assembleOptions == null
            ? DEFAULT_MAX_RESULT_CHARS : AiRetrievalContextAssembler.effectiveMaxChars(assembleOptions);
        List<AiRetrievalHitVo> fragments = AiRetrievalContextAssembler.assemble(hits, assembleOptions);
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库检索结果】共 ").append(fragments.size()).append(" 条\n");
        int index = 1;
        for (AiRetrievalHitVo hit : fragments) {
            sb.append("\n[").append(index++).append("] 文档：")
                .append(StringUtils.isBlank(hit.getDocName()) ? "未知文档" : hit.getDocName());
            if (StringUtils.isNotBlank(hit.getHeadingPath())) {
                sb.append(" ｜ §").append(hit.getHeadingPath().trim());
            }
            if (hit.getPageNo() != null) {
                sb.append(" ｜ p.").append(hit.getPageNo());
            }
            sb.append(" ｜ 切片 #").append(sliceLabel(hit))
                .append(" ｜ 相似度 ").append(hit.getScore() == null ? "-" : String.format("%.2f", hit.getScore()))
                .append("\n").append(StringUtils.trim(hit.getContent())).append("\n");
            if (sb.length() > maxChars) {
                break;
            }
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    /**
     * 把命中切片转换为结构化来源（供「参考来源」下发）。
     *
     * @param hits 命中切片
     * @return 来源视图列表
     */
    private List<AiSourceVo> toSources(List<AiRetrievalHitVo> hits) {
        List<AiSourceVo> sources = new ArrayList<>(hits.size());
        for (AiRetrievalHitVo hit : hits) {
            AiSourceVo source = new AiSourceVo();
            source.setDocId(hit.getDocId());
            source.setDocName(hit.getDocName());
            source.setHeadingPath(hit.getHeadingPath());
            source.setPageNo(hit.getPageNo());
            source.setChunkIndex(hit.getChunkIndex());
            source.setScore(hit.getScore());
            sources.add(source);
        }
        return sources;
    }

    /**
     * 切片号展示：相邻合并后显示区间（如 {@code 2-4}），单条显示单号。
     *
     * @param hit 命中片段
     * @return 切片号标签
     */
    private String sliceLabel(AiRetrievalHitVo hit) {
        if (hit.getChunkIndex() == null) {
            return "-";
        }
        if (hit.getChunkIndexEnd() != null && !hit.getChunkIndexEnd().equals(hit.getChunkIndex())) {
            return hit.getChunkIndex() + "-" + hit.getChunkIndexEnd();
        }
        return String.valueOf(hit.getChunkIndex());
    }

}
