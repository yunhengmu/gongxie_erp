package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeSaveDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改公告工具（写入类，执行前必须经人工确认）。
 *
 * <p>只修改显式提供的字段，未提供的字段沿用该公告现有值；公告 ID 须先用「搜索公告」取得，不要凭猜测填写。
 * 确认闸门走 AgentScope 框架原生 HITL（命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}，
 * 用户确认后恢复执行）。<b>修改不会重新向在线用户广播</b>（与页面「修改」一致，只有新增会推送）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class UpdateNoticeTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "update_notice";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "修改一条已有的通知公告（标题、类型、正文、状态、备注）。"
        + "只需提供要改动的字段，未提供的字段保持不变。属于写入类操作，执行前需要用户确认。"
        + "修改不会重新向在线用户推送，如需让用户看到最新内容请在结果中提醒。"
        + "公告 ID 必须先通过「搜索公告」取得，不要凭猜测填写。";

    /**
     * 修改公告所需权限
     */
    private static final String PERM_NOTICE_EDIT = "system:notice:edit";

    /**
     * 公告标题长度上限（与 SysNoticeBo 校验保持一致）
     */
    private static final int TITLE_MAX_LENGTH = 50;

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    /**
     * @param noticeService     公告服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验公告修改权限）
     */
    public UpdateNoticeTool(NoticeService noticeService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝修改公告");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_NOTICE_EDIT)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「通知公告修改」权限，已拒绝修改公告");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        NoticeSaveDTO dto = new NoticeSaveDTO();
        dto.setNoticeId(AiToolSupport.parseId(input.get("notice_id")));
        dto.setNoticeTitle(providedText(input, "notice_title"));
        dto.setNoticeType(providedText(input, "notice_type"));
        dto.setNoticeContent(providedText(input, "notice_content"));
        dto.setStatus(providedText(input, "status"));
        dto.setRemark(providedText(input, "remark"));
        dto.setOperatorUserId(operatorId);
        String invalid = validate(dto);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            noticeService.updateNoticeByAi(dto);
            return AiToolSupport.ok(param, TOOL_NAME,
                "公告已修改（notice_id：" + dto.getNoticeId() + "）。未提供的字段保持原值，本次修改不会重新推送给在线用户。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("修改公告失败：operatorId={}, noticeId={}", operatorId, dto.getNoticeId(), e);
            return AiToolSupport.fail(param, TOOL_NAME, "修改公告失败：" + e.getMessage());
        }
    }

    /**
     * 取文本入参（仅显式提供时生效：未提供返回 null＝保持原值，提供空串＝清空该字段）。
     *
     * @param input 原始入参
     * @param key   字段名
     * @return 文本；未提供时返回 null
     */
    private static String providedText(Map<String, Object> input, String key) {
        if (!input.containsKey(key)) {
            return null;
        }
        return StringUtils.trim(AiToolSupport.stringValue(input.get(key)));
    }

    /**
     * 校验入参（返回 null 表示通过；未提供的字段不校验）。
     *
     * @param dto 公告修改入参
     * @return 错误信息；通过时返回 null
     */
    private static String validate(NoticeSaveDTO dto) {
        if (dto.getNoticeId() == null) {
            return "公告 ID 不能为空且必须是数字（可先用「搜索公告」取得）";
        }
        if (dto.getNoticeTitle() != null
            && (StringUtils.isBlank(dto.getNoticeTitle()) || dto.getNoticeTitle().length() > TITLE_MAX_LENGTH)) {
            return "公告标题不能为空且不超过 " + TITLE_MAX_LENGTH + " 个字符";
        }
        if (dto.getNoticeType() != null && StringUtils.isNotBlank(dto.getNoticeType())
            && !"1".equals(dto.getNoticeType()) && !"2".equals(dto.getNoticeType())) {
            return "公告类型只能是 1（通知）或 2（公告）";
        }
        if (dto.getStatus() != null && StringUtils.isNotBlank(dto.getStatus())
            && !"0".equals(dto.getStatus()) && !"1".equals(dto.getStatus())) {
            return "公告状态只能是 0（正常）或 1（关闭）";
        }
        return null;
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("notice_id", Map.of("type", "string", "description", "要修改的公告 ID（数字字符串）"));
        properties.put("notice_title", Map.of("type", "string", "description", "新的公告标题，不超过 50 字，不改则不填"));
        properties.put("notice_type", Map.of("type", "string", "enum", List.of("1", "2"),
            "description", "公告类型：1 通知 / 2 公告"));
        properties.put("notice_content", Map.of("type", "string", "description", "新的公告正文，不改则不填"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "公告状态：0 正常 / 1 关闭"));
        properties.put("remark", Map.of("type", "string", "description", "备注；传空串表示清空"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("notice_id"));
        return schema;
    }

}