package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看公告详情工具（只读）。
 *
 * <p>按公告 ID 查询标题、类型、状态、正文、创建人与时间；需要「这条公告具体写了什么」时调用，
 * 不要凭标题猜测内容。公告 ID 可从「搜索公告」结果中获取。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetNoticeTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_notice";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按公告 ID 查看通知公告的完整内容（标题、类型、状态、正文、备注、"
        + "创建人、创建与更新时间）。属于只读操作。公告 ID 可从「搜索公告」结果中获取。";

    /**
     * 查看公告所需权限
     */
    private static final String PERM_NOTICE_QUERY = "system:notice:query";

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    /**
     * @param noticeService     公告服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验公告查询权限）
     */
    public GetNoticeTool(NoticeService noticeService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查看公告");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_NOTICE_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「通知公告查询」权限，已拒绝查看公告");
        }
        Long noticeId = AiToolSupport.parseId(AiToolSupport.input(param).get("notice_id"));
        if (noticeId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "公告 ID 不能为空且必须是数字");
        }
        try {
            NoticeDetailDTO notice = noticeService.selectNoticeDetailByAi(noticeId);
            if (notice == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "未找到公告（notice_id=" + noticeId + "），可能不存在或已被删除。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(notice));
        } catch (Exception e) {
            log.error("查看公告详情失败：noticeId={}", noticeId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查看公告失败：" + e.getMessage());
        }
    }

    /**
     * 渲染公告详情文本。
     *
     * @param notice 公告详情
     * @return 文本
     */
    private static String render(NoticeDetailDTO notice) {
        return "公告详情：\n"
            + "- 公告 ID：" + notice.getNoticeId() + "\n"
            + "- 标题：" + AiToolSupport.display(notice.getNoticeTitle()) + "\n"
            + "- 类型：" + AiToolSupport.noticeTypeText(notice.getNoticeType()) + "\n"
            + "- 状态：" + AiToolSupport.noticeStatusText(notice.getStatus()) + "\n"
            + "- 正文：\n" + AiToolSupport.display(notice.getNoticeContent()) + "\n"
            + "- 备注：" + AiToolSupport.display(notice.getRemark()) + "\n"
            + "- 创建人：" + AiToolSupport.display(notice.getCreateByName()) + "\n"
            + "- 创建时间：" + AiToolSupport.display(notice.getCreateTime()) + "\n"
            + "- 更新时间：" + AiToolSupport.display(notice.getUpdateTime());
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("notice_id", Map.of("type", "string", "description", "公告 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("notice_id"));
        return schema;
    }

}