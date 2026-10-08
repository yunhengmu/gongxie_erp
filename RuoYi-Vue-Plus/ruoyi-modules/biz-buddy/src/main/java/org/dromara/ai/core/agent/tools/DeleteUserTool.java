package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.UserService;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除用户工具（写入类，执行前必须经人工确认）。
 *
 * <p>逻辑删除并同时清理角色 / 岗位关联；超级管理员与操作人本人一律拒绝。
 * 确认闸门走 AgentScope 框架原生 HITL（命中 ask 规则时暂停并发 {@code RequireUserConfirmEvent}）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class DeleteUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "delete_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按用户 ID 删除一个用户（同时解除其角色与岗位绑定）。"
        + "超级管理员与当前操作人本人会被拒绝；属于写入类操作，执行前需要用户确认，"
        + "删除后该账号无法登录，请先与用户确认用户 ID 无误。如只是暂时停用，请改用「修改用户」把状态改为停用。"
        + "仅能删除提问用户数据范围内的用户。";

    /**
     * 删除用户所需权限
     */
    private static final String PERM_USER_REMOVE = "system:user:remove";

    private final UserService userService;

    private final PermissionService permissionService;

    /**
     * @param userService       用户服务（跨模块写入）
     * @param permissionService 权限服务（按用户校验用户删除权限）
     */
    public DeleteUserTool(UserService userService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝删除用户");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_USER_REMOVE)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「用户删除」权限，已拒绝删除用户");
        }
        Long userId = AiToolSupport.parseId(AiToolSupport.input(param).get("user_id"));
        if (userId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "用户 ID 不能为空且必须是数字");
        }
        try {
            userService.deleteUserByAi(userId, operatorId);
            return AiToolSupport.ok(param, TOOL_NAME, "用户已删除（user_id：" + userId + "）。");
        } catch (ServiceException e) {
            // 业务校验失败不打断整轮对话，交由模型说明情况
            return AiToolSupport.fail(param, TOOL_NAME, e.getMessage());
        } catch (Exception e) {
            log.error("删除用户失败：operatorId={}, userId={}", operatorId, userId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "删除用户失败：" + e.getMessage());
        }
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", Map.of("type", "string", "description", "要删除的用户 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("user_id"));
        return schema;
    }

}