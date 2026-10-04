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
 * 新增部门工具（写入类，执行前必须经人工确认）。
 *
 * <p>确认闸门走 AgentScope 框架原生 HITL：本工具是 {@link ToolBase} 子类，「智能体 × 工具」的决策
 * （{@code ai_agent_tool.decision}）已翻译为 allow / ask / deny——命中 ask 时框架暂停并发
 * {@code RequireUserConfirmEvent}，用户在对话页确认后框架恢复并真正执行本工具。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class CreateDeptTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "create_dept";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "在某个上级部门下新建一个部门。需要提供部门名称与上级部门 ID"
        + "（上级部门须存在且为正常状态、且在提问用户的数据范围内）。属于写入类操作，执行前需要用户确认；部门 ID 会由平台自动生成。";

    /**
     * 新增部门所需权限
     */
    private static final String PERM_DEPT_ADD = "system:dept:add";

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
     * @param permissionService 权限服务（按用户校验部门新增权限）
     */
    public CreateDeptTool(DeptService deptService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝新增部门");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_DEPT_ADD)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「部门新增」权限，已拒绝新增部门");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        DeptSaveDTO dto = new DeptSaveDTO();
        dto.setDeptName(StringUtils.trim(AiToolSupport.stringValue(input.get("dept_name"))));
        dto.setParentId(AiToolSupport.parseId(input.get("parent_id")));
        dto.setOrderNum(parseOrderNum(input.get("order_num")));
        dto.setLeader(AiToolSupport.parseId(input.get("leader")));
        dto.setPhone(optionalText(input, "phone"));
        dto.setEmail(optionalText(input, "email"));
        dto.setDeptCategory(optionalText(input, "dept_category"));
        dto.setStatus(StringUtils.trim(AiToolSupport.stringValue(input.get("status"))));
        dto.setOperatorUserId(operatorId);
        String invalid = validate(dto, input.get("order_num") != null);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            Long deptId = deptService.createDeptByAi(dto);
            return AiToolSupport.ok(param, TOOL_NAME,
                "部门已新增（dept_id：" + deptId + "，名称：" + dto.getDeptName()
                    + "，上级 parent_id：" + dto.getParentId() + "）。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("新增部门失败：operatorId={}, deptName={}", operatorId, dto.getDeptName(), e);
            return AiToolSupport.fail(param, TOOL_NAME, "新增部门失败：" + e.getMessage());
        }
    }

    /**
     * 取可选文本入参（缺省或空白一律落 null，避免写入空串）。
     *
     * @param input 原始入参
     * @param key   字段名
     * @return 文本；未提供或空白时返回 null
     */
    private static String optionalText(Map<String, Object> input, String key) {
        String value = StringUtils.trim(AiToolSupport.stringValue(input.get(key)));
        return StringUtils.isBlank(value) ? null : value;
    }

    /**
     * 解析显示顺序（未提供时取 0；非数字或负数返回 null 以触发校验失败）。
     *
     * @param raw 原始值
     * @return 显示顺序；非法时返回 null
     */
    private static Integer parseOrderNum(Object raw) {
        if (raw == null || StringUtils.isBlank(String.valueOf(raw))) {
            return 0;
        }
        try {
            int value = Integer.parseInt(StringUtils.trim(String.valueOf(raw)));
            return value < 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 校验入参（返回 null 表示通过）。
     *
     * @param dto            部门信息
     * @param orderNumPassed 入参是否显式提供了显示顺序（用于区分「未填」与「非法」）
     * @return 错误信息；通过时返回 null
     */
    private String validate(DeptSaveDTO dto, boolean orderNumPassed) {
        if (StringUtils.isBlank(dto.getDeptName())) {
            return "部门名称不能为空";
        }
        if (dto.getDeptName().length() > NAME_MAX_LENGTH) {
            return "部门名称不能超过 " + NAME_MAX_LENGTH + " 个字符";
        }
        if (dto.getParentId() == null) {
            return "上级部门 ID 不能为空且必须是数字";
        }
        if (orderNumPassed && dto.getOrderNum() == null) {
            return "显示顺序必须是不小于 0 的整数";
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
        properties.put("dept_name", Map.of("type", "string", "description", "部门名称，不超过 30 字，同一上级下不能重名"));
        properties.put("parent_id", Map.of("type", "string", "description", "上级部门 ID（数字字符串），须存在且为正常状态"));
        properties.put("order_num", Map.of("type", "integer", "description", "显示顺序，默认 0，越小越靠前"));
        properties.put("leader", Map.of("type", "string", "description", "负责人用户 ID（数字字符串），可空"));
        properties.put("phone", Map.of("type", "string", "description", "联系电话，不超过 11 位，可空"));
        properties.put("email", Map.of("type", "string", "description", "邮箱，不超过 50 字，可空"));
        properties.put("dept_category", Map.of("type", "string", "description", "部门类别编码，不超过 100 字，可空"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "部门状态：0 正常（默认）/ 1 停用"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("dept_name", "parent_id"));
        return schema;
    }

}
