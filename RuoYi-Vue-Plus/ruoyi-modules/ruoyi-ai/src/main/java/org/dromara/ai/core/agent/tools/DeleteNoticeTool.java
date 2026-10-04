package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.NoticeService;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除公告工具（写入类，执行前必须经人工确认）。
 *
 * <p>按公告 ID 物理删除（与页面「删除」一致，删除后不可恢复）。公告 ID 须先用「搜索公告」取得，
 * 确认闸门走 AgentScope 框架原生 HITL（命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class DeleteNoticeTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "delete_notice";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按公告 ID 删除一条通知公告，删除后不可恢复。"
        + "属于写入类操作，执行前需要用户确认；公告 ID 必须先通过「搜索公告」取得，不要凭猜测填写。"
        + "如果只是想让公告不再展示，请改用「修改公告」把状态改为关闭（1）。";

    /**
     * 删除公告所需权限
     */
    private static final String PERM_NOTICE_REMOVE = "system:notice:remove";

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    /**
     * @param noticeService     公告服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验公告删除权限）
     */
    public DeleteNoticeTool(NoticeService noticeService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(false));
        this.noticeService = noticeService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝删除公告");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_NOTICE_REMOVE)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「通知公告删除」权限，已拒绝删除公告");
        }
        Long noticeId = AiToolSupport.parseId(AiToolSupport.input(param).get("notice_id"));
        if (noticeId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "公告 ID 不能为空且必须是数字（可先用「搜索公告」取得）");
        }
        try {
            noticeService.deleteNoticeByAi(noticeId);
            return AiToolSupport.ok(param, TOOL_NAME, "公告已删除（notice_id：" + noticeId + "），删除后不可恢复。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("删除公告失败：operatorId={}, noticeId={}", operatorId, noticeId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "删除公告失败：" + e.getMessage());
        }
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("notice_id", Map.of("type", "string", "description", "要删除的公告 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("notice_id"));
        return schema;
    }

}