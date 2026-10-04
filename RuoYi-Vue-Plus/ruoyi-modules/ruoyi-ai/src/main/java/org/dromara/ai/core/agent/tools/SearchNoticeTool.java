package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索公告工具（只读）。
 *
 * <p>按标题关键词模糊检索（可附类型与状态筛选），返回公告 ID、标题、类型、状态、创建人与创建时间，
 * 用于回答「最近发了哪些公告」或为「查看 / 修改 / 删除公告」取得公告 ID；正文不在列表中返回，
 * 需要正文时改调「查看公告详情」。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class SearchNoticeTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "search_notice";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按标题关键词搜索通知公告（可附带类型与状态筛选），"
        + "返回公告 ID、标题、类型、状态、创建人与创建时间，按最新在前排序。"
        + "列表不含正文，需要正文请再用「查看公告详情」。属于只读操作。关键词留空则列出公告。";

    /**
     * 搜索公告所需权限
     */
    private static final String PERM_NOTICE_LIST = "system:notice:list";

    /**
     * 单次返回的公告条数上限（超出只回前 N 条并提示缩小范围）
     */
    private static final int MAX_RESULT = 50;

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    /**
     * @param noticeService     公告服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验公告列表权限）
     */
    public SearchNoticeTool(NoticeService noticeService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.noticeService = noticeService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝搜索公告");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_NOTICE_LIST)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「通知公告列表」权限，已拒绝搜索公告");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = StringUtils.trim(AiToolSupport.stringValue(input.get("keyword")));
        String noticeType = StringUtils.trim(AiToolSupport.stringValue(input.get("notice_type")));
        String status = StringUtils.trim(AiToolSupport.stringValue(input.get("status")));
        if (!StringUtils.isBlank(noticeType) && !"1".equals(noticeType) && !"2".equals(noticeType)) {
            return AiToolSupport.fail(param, TOOL_NAME, "公告类型只能是 1（通知）或 2（公告）");
        }
        if (!StringUtils.isBlank(status) && !"0".equals(status) && !"1".equals(status)) {
            return AiToolSupport.fail(param, TOOL_NAME, "公告状态只能是 0（正常）或 1（关闭）");
        }
        try {
            List<NoticeDetailDTO> notices = noticeService.searchNoticesByAi(keyword, noticeType, status);
            return AiToolSupport.ok(param, TOOL_NAME, render(notices, keyword, noticeType, status));
        } catch (Exception e) {
            log.error("搜索公告失败：keyword={}, noticeType={}, status={}", keyword, noticeType, status, e);
            return AiToolSupport.fail(param, TOOL_NAME, "搜索公告失败：" + e.getMessage());
        }
    }

    /**
     * 渲染搜索结果文本。
     *
     * @param notices    公告列表
     * @param keyword    关键词
     * @param noticeType 类型筛选
     * @param status     状态筛选
     * @return 文本
     */
    private static String render(List<NoticeDetailDTO> notices, String keyword, String noticeType, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(notices.size()).append(" 条公告");
        if (StringUtils.isNotBlank(keyword)) {
            sb.append("（标题关键词：").append(keyword).append("）");
        }
        if (StringUtils.isNotBlank(noticeType)) {
            sb.append("（类型：").append(AiToolSupport.noticeTypeText(noticeType)).append("）");
        }
        if (StringUtils.isNotBlank(status)) {
            sb.append("（状态：").append(AiToolSupport.noticeStatusText(status)).append("）");
        }
        if (notices.isEmpty()) {
            sb.append("：没有匹配的公告。");
            return sb.toString();
        }
        sb.append("：\n");
        int limit = Math.min(notices.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            NoticeDetailDTO notice = notices.get(i);
            sb.append("- notice_id=").append(notice.getNoticeId())
                .append(" | 标题=").append(AiToolSupport.display(notice.getNoticeTitle()))
                .append(" | 类型=").append(AiToolSupport.noticeTypeText(notice.getNoticeType()))
                .append(" | 状态=").append(AiToolSupport.noticeStatusText(notice.getStatus()))
                .append(" | 创建人=").append(AiToolSupport.display(notice.getCreateByName()))
                .append(" | 创建时间=").append(AiToolSupport.display(notice.getCreateTime()))
                .append("\n");
        }
        if (notices.size() > limit) {
            sb.append("（仅列出前 ").append(limit).append(" 条，请用更精确的关键词或筛选条件缩小范围）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("keyword", Map.of("type", "string", "description", "标题关键词（模糊匹配），留空列出公告"));
        properties.put("notice_type", Map.of("type", "string", "enum", List.of("1", "2"),
            "description", "公告类型：1 通知 / 2 公告，留空不限"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "公告状态：0 正常 / 1 关闭，留空不限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

}