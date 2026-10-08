package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserSaveDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改用户工具（写入类，执行前必须经人工确认）。
 *
 * <p>只修改显式提供的字段，未提供的字段沿用该用户现有值；确认闸门走 AgentScope 框架原生 HITL
 * （命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}，用户确认后恢复执行）。
 * <b>不修改密码</b>——密码重置在「用户管理」页单独操作。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class UpdateUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "update_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "修改某个用户的信息（昵称、所属部门、手机号、邮箱、性别、账号状态、"
        + "角色、岗位、备注等）。只需提供要改动的字段，未提供的字段保持不变。属于写入类操作，执行前需要用户确认。"
        + "不能修改超级管理员，也不能修改密码（密码在「用户管理」页重置）。"
        + "角色传空数组表示清空该用户的全部角色。"
        + "仅能修改提问用户数据范围内的用户，调整后的部门也须在范围内。";

    /**
     * 修改用户所需权限
     */
    private static final String PERM_USER_EDIT = "system:user:edit";

    /**
     * 用户账号长度上下限（与 SysUserBo 校验保持一致）
     */
    private static final int USER_NAME_MIN_LENGTH = 2;

    /**
     * 用户账号长度上限
     */
    private static final int USER_NAME_MAX_LENGTH = 30;

    /**
     * 用户昵称长度上限
     */
    private static final int NICK_NAME_MAX_LENGTH = 30;

    /**
     * 邮箱长度上限
     */
    private static final int EMAIL_MAX_LENGTH = 50;

    /**
     * 手机号码长度上限
     */
    private static final int PHONE_MAX_LENGTH = 11;

    private final UserService userService;

    private final PermissionService permissionService;

    /**
     * @param userService       用户服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验用户修改权限）
     */
    public UpdateUserTool(UserService userService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(false)
            .concurrencySafe(false));
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝修改用户");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_USER_EDIT)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「用户修改」权限，已拒绝修改用户");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUserId(AiToolSupport.parseId(input.get("user_id")));
        dto.setUserName(providedText(input, "user_name"));
        dto.setNickName(providedText(input, "nick_name"));
        dto.setDeptId(input.containsKey("dept_id") ? AiToolSupport.parseId(input.get("dept_id")) : null);
        dto.setPhoneNumber(providedText(input, "phone"));
        dto.setEmail(providedText(input, "email"));
        dto.setGender(providedText(input, "gender"));
        dto.setStatus(providedText(input, "status"));
        dto.setRemark(providedText(input, "remark"));
        dto.setRoleIds(AiToolSupport.parseIds(input.get("role_ids")));
        dto.setPostIds(AiToolSupport.parseIds(input.get("post_ids")));
        dto.setOperatorUserId(operatorId);
        String invalid = validate(dto, input);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            userService.updateUserByAi(dto);
            StringBuilder sb = new StringBuilder();
            sb.append("用户已修改（user_id：").append(dto.getUserId()).append("）。未提供的字段保持原值。");
            if (dto.getRoleIds() != null && dto.getRoleIds().length == 0) {
                sb.append("\n- 注意：角色已清空，该账号将失去全部菜单权限。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, sb.toString());
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("修改用户失败：operatorId={}, userId={}", operatorId, dto.getUserId(), e);
            return AiToolSupport.fail(param, TOOL_NAME, "修改用户失败：" + e.getMessage());
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
     * @param dto   用户信息
     * @param input 原始入参
     * @return 错误信息；通过时返回 null
     */
    private String validate(UserSaveDTO dto, Map<String, Object> input) {
        if (dto.getUserId() == null) {
            return "用户 ID 不能为空且必须是数字";
        }
        if (input.containsKey("dept_id") && dto.getDeptId() == null) {
            return "部门 ID 必须是数字";
        }
        if (dto.getUserName() != null) {
            int length = dto.getUserName().length();
            if (length < USER_NAME_MIN_LENGTH || length > USER_NAME_MAX_LENGTH) {
                return "用户账号长度必须在 " + USER_NAME_MIN_LENGTH + " 到 " + USER_NAME_MAX_LENGTH + " 个字符之间";
            }
        }
        if (dto.getNickName() != null && (StringUtils.isBlank(dto.getNickName())
            || dto.getNickName().length() > NICK_NAME_MAX_LENGTH)) {
            return "用户昵称不能为空且不超过 " + NICK_NAME_MAX_LENGTH + " 个字符";
        }
        if (dto.getPhoneNumber() != null && dto.getPhoneNumber().length() > PHONE_MAX_LENGTH) {
            return "手机号码不能超过 " + PHONE_MAX_LENGTH + " 个字符";
        }
        if (dto.getEmail() != null && StringUtils.isNotBlank(dto.getEmail())) {
            if (dto.getEmail().length() > EMAIL_MAX_LENGTH) {
                return "邮箱不能超过 " + EMAIL_MAX_LENGTH + " 个字符";
            }
            if (!dto.getEmail().contains("@")) {
                return "邮箱格式不正确：" + dto.getEmail();
            }
        }
        if (dto.getGender() != null && StringUtils.isNotBlank(dto.getGender())
            && !"0".equals(dto.getGender()) && !"1".equals(dto.getGender()) && !"2".equals(dto.getGender())) {
            return "性别只能是 0（男）/ 1（女）/ 2（未知）";
        }
        if (dto.getStatus() != null && StringUtils.isNotBlank(dto.getStatus())
            && !"0".equals(dto.getStatus()) && !"1".equals(dto.getStatus())) {
            return "账号状态只能是 0（正常）或 1（停用）";
        }
        String roleInvalid = AiToolSupport.validateIds(input.get("role_ids"));
        if (roleInvalid != null) {
            return "角色 " + roleInvalid;
        }
        String postInvalid = AiToolSupport.validateIds(input.get("post_ids"));
        if (postInvalid != null) {
            return "岗位 " + postInvalid;
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
        properties.put("user_id", Map.of("type", "string", "description", "要修改的用户 ID（数字字符串）"));
        properties.put("user_name", Map.of("type", "string", "description", "新的用户账号，2~30 位且平台内唯一，不改则不填"));
        properties.put("nick_name", Map.of("type", "string", "description", "新的用户昵称，不超过 30 字"));
        properties.put("dept_id", Map.of("type", "string", "description", "新的所属部门 ID（数字字符串），不改则不填"));
        properties.put("phone", Map.of("type", "string", "description", "手机号码，不超过 11 位；传空串表示清空"));
        properties.put("email", Map.of("type", "string", "description", "邮箱，不超过 50 字；传空串表示清空"));
        properties.put("gender", Map.of("type", "string", "enum", List.of("0", "1", "2"),
            "description", "性别：0 男 / 1 女 / 2 未知"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "账号状态：0 正常 / 1 停用"));
        properties.put("role_ids", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "角色 ID 列表（数字字符串）：不填＝不改，传空数组＝清空全部角色"));
        properties.put("post_ids", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "岗位 ID 列表（数字字符串）：不填＝不改，传空数组＝清空全部岗位"));
        properties.put("remark", Map.of("type", "string", "description", "备注；传空串表示清空"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("user_id"));
        return schema;
    }

}