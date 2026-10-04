package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.domain.NoticeDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布系统公告工具（写入类，执行前必须经人工确认）。
 *
 * <p>确认闸门走 AgentScope 框架原生 HITL：本工具是 {@link ToolBase} 子类，权限判定沿用
 * {@code ToolBase#checkPermissions} 的默认实现（{@code passthrough}），即由框架按装配期写入的
 * {@code PermissionContextState} 规则判定——「智能体 × 工具」的决策（{@code ai_agent_tool.decision}）
 * 已翻译为 allow / ask / deny：命中 ask 时框架暂停并发 {@code RequireUserConfirmEvent}，
 * 用户在对话页确认后框架以 {@code ConfirmResult} 恢复并真正执行本工具；拒绝则不执行。</p>
 *
 * <p>工具运行在 Reactor 线程，Sa-Token 的线程上下文不可用，故操作人取自
 * {@link ToolCallParam#getRuntimeContext()}，权限校验使用不依赖线程上下文的 {@link PermissionService}。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class CreateNoticeTool extends ToolBase {

    /**
     * 工具名称
     */
    public static final String TOOL_NAME = "create_notice";

    /**
     * 工具描述
     */
    private static final String DESCRIPTION = "在平台内发布一条系统公告，并向在线用户广播。属于写入类操作："
        + "调用时平台会自动弹出人工确认卡，你直接调用即可，不要在回答中另行征求用户确认。";

    /**
     * 公告标题长度上限（与 SysNoticeBo 校验保持一致）
     */
    private static final int TITLE_MAX_LENGTH = 50;

    /**
     * 发布公告所需权限
     */
    private static final String PERM_NOTICE_ADD = "system:notice:add";

    /**
     * 超级管理员通配权限
     */
    private static final String PERM_ALL = "*:*:*";

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    /**
     * @param noticeService     系统公告服务（落库 + 广播）
     * @param permissionService 权限服务（按用户校验发布权限）
     */
    public CreateNoticeTool(NoticeService noticeService, PermissionService permissionService) {
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
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String id = use == null ? null : use.getId();
        String name = use == null ? TOOL_NAME : use.getName();
        Long operatorId = resolveOperatorId(param);
        if (operatorId == null) {
            return Mono.just(ToolResultBlock.error("无法识别当前操作人，已拒绝发布公告").withIdAndName(id, name));
        }
        if (!hasNoticePermission(operatorId)) {
            return Mono.just(ToolResultBlock.error("当前用户无「通知公告新增」权限，已拒绝发布公告").withIdAndName(id, name));
        }
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String title = StringUtils.trim(stringValue(input.get("notice_title")));
        String content = StringUtils.trim(stringValue(input.get("notice_content")));
        if (StringUtils.isBlank(title)) {
            return Mono.just(ToolResultBlock.error("公告标题不能为空").withIdAndName(id, name));
        }
        if (title.length() > TITLE_MAX_LENGTH) {
            return Mono.just(ToolResultBlock.error("公告标题不能超过 " + TITLE_MAX_LENGTH + " 个字符").withIdAndName(id, name));
        }
        if (StringUtils.isBlank(content)) {
            return Mono.just(ToolResultBlock.error("公告正文不能为空").withIdAndName(id, name));
        }
        NoticeDTO dto = new NoticeDTO();
        dto.setNoticeTitle(title);
        dto.setNoticeType(stringValue(input.get("notice_type")));
        dto.setNoticeContent(content);
        dto.setStatus(stringValue(input.get("status")));
        dto.setOperatorUserId(operatorId);
        try {
            Long noticeId = noticeService.createNoticeByAi(dto);
            return Mono.just(ToolResultBlock.text("公告已发布（通知 ID：" + noticeId + "），标题：" + title)
                .withIdAndName(id, name));
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return Mono.just(ToolResultBlock.error(e.getMessage()).withIdAndName(id, name));
        } catch (Exception e) {
            log.error("发布系统公告失败，operatorId={}, title={}", operatorId, title, e);
            return Mono.just(ToolResultBlock.error("发布公告失败：" + e.getMessage()).withIdAndName(id, name));
        }
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("notice_title", Map.of("type", "string", "description", "公告标题，不超过 50 字"));
        properties.put("notice_type", Map.of("type", "string", "enum", List.of("1", "2"),
            "description", "公告类型：1 通知 2 公告，默认 1"));
        properties.put("notice_content", Map.of("type", "string", "description", "公告正文"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "公告状态：0 正常 1 关闭，默认 0"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("notice_title", "notice_content"));
        return schema;
    }

    /**
     * 从运行时上下文解析操作人。
     *
     * @param param 工具调用参数
     * @return 操作人用户 ID；无法解析时返回 null
     */
    private Long resolveOperatorId(ToolCallParam param) {
        return parseId(param == null || param.getRuntimeContext() == null
            ? null : param.getRuntimeContext().getUserId());
    }

    /**
     * 解析数字 ID。
     *
     * @param raw 原始字符串
     * @return 数字 ID；非法时返回 null
     */
    private Long parseId(String raw) {
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
     * 校验操作人是否具备发布公告权限。
     *
     * @param operatorId 操作人用户 ID
     * @return 是否具备权限
     */
    private boolean hasNoticePermission(Long operatorId) {
        Set<String> perms = permissionService.getMenuPermission(operatorId);
        if (perms == null || perms.isEmpty()) {
            return false;
        }
        return perms.contains(PERM_ALL) || perms.contains(PERM_NOTICE_ADD);
    }

    /**
     * 安全转字符串。
     *
     * @param value 原始值
     * @return 字符串（null 转空串）
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}