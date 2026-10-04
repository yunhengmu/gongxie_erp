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
 * 新增用户工具（写入类，执行前必须经人工确认）。
 *
 * <p>确认闸门走 AgentScope 框架原生 HITL：本工具是 {@link ToolBase} 子类，「智能体 × 工具」的决策
 * （{@code ai_agent_tool.decision}）已翻译为 allow / ask / deny——命中 ask 时框架暂停并发
 * {@code RequireUserConfirmEvent}，用户在对话页确认后框架恢复并真正执行本工具。</p>
 *
 * <p><b>初始密码不由模型提供</b>：由平台配置项 {@code sys.user.initPassword} 决定（与「用户导入」
 * 同一口径），避免明文口令流入模型上下文与工具调用审计表；请在结果提示中引导用户到「用户管理」页重置。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class CreateUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "create_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "新建一个用户账号。需要提供用户账号与昵称（账号与昵称均不超过 30 字，"
        + "账号在平台内唯一、长度 2~30 位）；可选提供所属部门、手机号、邮箱、性别、账号状态、角色与岗位。"
        + "属于写入类操作，执行前需要用户确认。初始密码由平台配置项统一指定（不需要也不允许你提供密码），"
        + "请提示用户在「用户管理」页重置密码；未提供角色时该账号暂无可用的菜单权限。"
        + "所属部门（未指定时取你所在部门）须在提问用户的数据范围内。";

    /**
     * 新增用户所需权限
     */
    private static final String PERM_USER_ADD = "system:user:add";

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
     * @param permissionService 权限服务（按用户校验用户新增权限）
     */
    public CreateUserTool(UserService userService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝新增用户");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_USER_ADD)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「用户新增」权限，已拒绝新增用户");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUserName(StringUtils.trim(AiToolSupport.stringValue(input.get("user_name"))));
        dto.setNickName(StringUtils.trim(AiToolSupport.stringValue(input.get("nick_name"))));
        dto.setDeptId(AiToolSupport.parseId(input.get("dept_id")));
        dto.setPhoneNumber(optionalText(input, "phone"));
        dto.setEmail(optionalText(input, "email"));
        dto.setGender(optionalText(input, "gender"));
        dto.setStatus(StringUtils.trim(AiToolSupport.stringValue(input.get("status"))));
        dto.setRemark(optionalText(input, "remark"));
        dto.setRoleIds(AiToolSupport.parseIds(input.get("role_ids")));
        dto.setPostIds(AiToolSupport.parseIds(input.get("post_ids")));
        dto.setOperatorUserId(operatorId);
        String invalid = validate(dto, input);
        if (invalid != null) {
            return AiToolSupport.fail(param, TOOL_NAME, invalid);
        }
        try {
            Long userId = userService.createUserByAi(dto);
            StringBuilder sb = new StringBuilder();
            sb.append("用户已新增（user_id：").append(userId)
                .append("，账号：").append(dto.getUserName())
                .append("，昵称：").append(dto.getNickName()).append("）。");
            sb.append("\n- 初始密码：由平台配置项 sys.user.initPassword 统一指定，"
                + "请提示用户登录后立即在「个人中心」修改，或由管理员在「用户管理」页重置。");
            if (dto.getRoleIds() == null || dto.getRoleIds().length == 0) {
                sb.append("\n- 注意：本次未分配角色，该账号登录后暂无菜单权限，"
                    + "请在「用户管理」页为其分配角色。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, sb.toString());
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("新增用户失败：operatorId={}, userName={}", operatorId, dto.getUserName(), e);
            return AiToolSupport.fail(param, TOOL_NAME, "新增用户失败：" + e.getMessage());
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
     * 校验入参（返回 null 表示通过）。
     *
     * @param dto   用户信息
     * @param input 原始入参
     * @return 错误信息；通过时返回 null
     */
    private String validate(UserSaveDTO dto, Map<String, Object> input) {
        if (StringUtils.isBlank(dto.getUserName())) {
            return "用户账号不能为空";
        }
        if (dto.getUserName().length() < USER_NAME_MIN_LENGTH || dto.getUserName().length() > USER_NAME_MAX_LENGTH) {
            return "用户账号长度必须在 " + USER_NAME_MIN_LENGTH + " 到 " + USER_NAME_MAX_LENGTH + " 个字符之间";
        }
        if (StringUtils.isBlank(dto.getNickName())) {
            return "用户昵称不能为空";
        }
        if (dto.getNickName().length() > NICK_NAME_MAX_LENGTH) {
            return "用户昵称不能超过 " + NICK_NAME_MAX_LENGTH + " 个字符";
        }
        if (input.get("dept_id") != null && dto.getDeptId() == null) {
            return "部门 ID 必须是数字";
        }
        if (!StringUtils.isBlank(dto.getPhoneNumber()) && dto.getPhoneNumber().length() > PHONE_MAX_LENGTH) {
            return "手机号码不能超过 " + PHONE_MAX_LENGTH + " 个字符";
        }
        if (!StringUtils.isBlank(dto.getEmail())) {
            if (dto.getEmail().length() > EMAIL_MAX_LENGTH) {
                return "邮箱不能超过 " + EMAIL_MAX_LENGTH + " 个字符";
            }
            if (!dto.getEmail().contains("@")) {
                return "邮箱格式不正确：" + dto.getEmail();
            }
        }
        if (!StringUtils.isBlank(dto.getGender()) && !"0".equals(dto.getGender())
            && !"1".equals(dto.getGender()) && !"2".equals(dto.getGender())) {
            return "性别只能是 0（男）/ 1（女）/ 2（未知）";
        }
        if (!StringUtils.isBlank(dto.getStatus()) && !"0".equals(dto.getStatus()) && !"1".equals(dto.getStatus())) {
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
        properties.put("user_name", Map.of("type", "string", "description", "用户账号，2~30 位且平台内唯一"));
        properties.put("nick_name", Map.of("type", "string", "description", "用户昵称，不超过 30 字"));
        properties.put("dept_id", Map.of("type", "string", "description", "所属部门 ID（数字字符串），留空取操作人所属部门"));
        properties.put("phone", Map.of("type", "string", "description", "手机号码，不超过 11 位，可空"));
        properties.put("email", Map.of("type", "string", "description", "邮箱，不超过 50 字，可空"));
        properties.put("gender", Map.of("type", "string", "enum", List.of("0", "1", "2"),
            "description", "性别：0 男（默认）/ 1 女 / 2 未知"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "账号状态：0 正常（默认）/ 1 停用"));
        properties.put("role_ids", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "角色 ID 列表（数字字符串）。留空则新建的账号暂无菜单权限，需事后在「用户管理」页分配"));
        properties.put("post_ids", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "岗位 ID 列表（数字字符串），可空"));
        properties.put("remark", Map.of("type", "string", "description", "备注，可空"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("user_name", "nick_name"));
        return schema;
    }

}