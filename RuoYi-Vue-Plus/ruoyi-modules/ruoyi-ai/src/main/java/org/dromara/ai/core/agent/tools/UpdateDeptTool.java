package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptSaveDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改部门工具（写入类，执行前必须经人工确认）。
 *
 * <p>只修改显式提供的字段，未提供的字段沿用该部门现有值；确认闸门走 AgentScope 框架原生 HITL
 * （命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}，用户确认后恢复执行）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class UpdateDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "update_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "修改某个部门的信息（名称、上级部门、显示顺序、负责人、联系方式、状态等）。"
        + "只需提供要改动的字段，未提供的字段保持不变。属于写入类操作，执行前需要用户确认。"
        + "停用部门前需确保该部门没有未停用的下级部门、且没有已分配用户。"
        + "仅能修改提问用户数据范围内的部门，调整后的上级部门也须在范围内。";

    /**
     * 修改部门所需权限
     */
    private static final String PERM_DEPT_EDIT = "system:dept:edit";

    /**
     * 部门名称长度上限（与 SysDeptBo 校验保持一致）
     */
    private static final int NAME_MAX_LENGTH = 30;

    /**
     * 部门类别编码长度上限
     */
    private static final int CATEGORY_MAX_LENGTH = 100;

    /**
     * 联系电话长度上限
     */
    private static final int PHONE_MAX_LENGTH = 11;

    /**
     * 邮箱长度上限
     */
    private static final int EMAIL_MAX_LENGTH = 50;

    private final DeptService deptService;

    private final PermissionService permissionService;

    /**
     * @param deptService       部门服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验部门修改权限）
     */
    public UpdateDeptTool(DeptService deptService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(false));
        this.deptService = deptService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝修改部门");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_DEPT_EDIT)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「部门修改」权限，已拒绝修改部门");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        DeptSaveDTO dto = new DeptSaveDTO();
        dto.setDeptId(AiToolSupport.parseId(input.get("dept_id")));
        dto.setParentId(input.containsKey("parent_id") ? AiToolSupport.parseId(input.get("parent_id")) : null);
        dto.setDeptName(providedText(input, "dept_name"));
        dto.setOrderNum(input.containsKey("order_num") ? parseOrderNum(input.get("order_num")) : null);
        dto.setLeader(input.containsKey("leader") ? AiToolSupport.parseId(input.get("leader")) : null);
        dto.setPhone(providedText(input, "phone"));
        dto.setEmail(providedText(input, "email"));
        dto.setDeptCategory(providedText(input, "dept_category"));
        dto.setStatus(providedText(input, "status"));
        dto.setOperatorUserId(operatorId);
        String invalid = validate(dto, input);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            deptService.updateDeptByAi(dto);
            return AiToolSupport.ok(param, TOOL_NAME,
                "部门已修改（dept_id：" + dto.getDeptId() + "）。未提供的字段保持原值。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("修改部门失败：operatorId={}, deptId={}", operatorId, dto.getDeptId(), e);
            return AiToolSupport.fail(param, TOOL_NAME, "修改部门失败：" + e.getMessage());
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
     * 解析显示顺序（非数字或负数返回 null 以触发校验失败）。
     *
     * @param raw 原始值
     * @return 显示顺序；非法时返回 null
     */
    private static Integer parseOrderNum(Object raw) {
        if (raw == null || StringUtils.isBlank(String.valueOf(raw))) {
            return null;
        }
        try {
            int value = Integer.parseInt(StringUtils.trim(String.valueOf(raw)));
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 校验入参（返回 null 表示通过；未提供的字段不校验）。
     *
     * @param dto   部门信息
     * @param input 原始入参
     * @return 错误信息；通过时返回 null
     */
    private String validate(DeptSaveDTO dto, Map<String, Object> input) {
        if (dto.getDeptId() == null) {
            return "部门 ID 不能为空且必须是数字";
        }
        if (input.containsKey("parent_id") && AiToolSupport.parseId(input.get("parent_id")) == null) {
            return "上级部门 ID 必须是数字";
        }
        if (!StringUtils.isBlank(dto.getDeptName()) && dto.getDeptName().length() > NAME_MAX_LENGTH) {
            return "部门名称不能超过 " + NAME_MAX_LENGTH + " 个字符";
        }
        if (input.containsKey("order_num") && dto.getOrderNum() == null) {
            return "显示顺序必须是不小于 0 的整数";
        }
        if (input.containsKey("leader") && dto.getLeader() == null) {
            return "负责人用户 ID 必须是数字";
        }
        if (!StringUtils.isBlank(dto.getDeptCategory()) && dto.getDeptCategory().length() > CATEGORY_MAX_LENGTH) {
            return "部门类别编码不能超过 " + CATEGORY_MAX_LENGTH + " 个字符";
        }
        if (!StringUtils.isBlank(dto.getPhone()) && dto.getPhone().length() > PHONE_MAX_LENGTH) {
            return "联系电话不能超过 " + PHONE_MAX_LENGTH + " 个字符";
        }
        if (!StringUtils.isBlank(dto.getEmail())) {
            if (dto.getEmail().length() > EMAIL_MAX_LENGTH) {
                return "邮箱不能超过 " + EMAIL_MAX_LENGTH + " 个字符";
            }
            if (!dto.getEmail().contains("@")) {
                return "邮箱格式不正确：" + dto.getEmail();
            }
        }
        if (!StringUtils.isBlank(dto.getStatus()) && !"0".equals(dto.getStatus()) && !"1".equals(dto.getStatus())) {
            return "部门状态只能是 0（正常）或 1（停用）";
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
        properties.put("dept_id", Map.of("type", "string", "description", "要修改的部门 ID（数字字符串）"));
        properties.put("dept_name", Map.of("type", "string", "description", "部门名称，不超过 30 字，同一上级下不能重名"));
        properties.put("parent_id", Map.of("type", "string", "description", "新的上级部门 ID（数字字符串），不改上级则不填"));
        properties.put("order_num", Map.of("type", "integer", "description", "显示顺序，不小于 0"));
        properties.put("leader", Map.of("type", "string", "description", "负责人用户 ID（数字字符串）"));
        properties.put("phone", Map.of("type", "string", "description", "联系电话，不超过 11 位"));
        properties.put("email", Map.of("type", "string", "description", "邮箱，不超过 50 字"));
        properties.put("dept_category", Map.of("type", "string", "description", "部门类别编码，不超过 100 字"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "部门状态：0 正常 / 1 停用"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("dept_id"));
        return schema;
    }

}
