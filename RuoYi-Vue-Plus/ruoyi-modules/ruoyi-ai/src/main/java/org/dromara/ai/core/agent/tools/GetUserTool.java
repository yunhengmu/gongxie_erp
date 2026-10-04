package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看用户详情工具（只读）。
 *
 * <p>按用户 ID 查询账号、昵称、所属部门、联系方式、性别、状态、角色与最后登录时间；
 * 需要「某人属于哪个部门」「某人有哪些角色」等事实时调用，不要凭猜测回答。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按用户 ID 查询用户的详细信息（账号、昵称、所属部门、"
        + "手机号、邮箱、性别、账号状态、角色、最后登录时间、创建时间）。属于只读操作，"
        + "不含密码。用户 ID 可从「搜索用户」结果中获取。"
        + "仅能查看当前提问用户数据范围内的用户。";

    /**
     * 查看用户所需权限
     */
    private static final String PERM_USER_QUERY = "system:user:query";

    private final UserService userService;

    private final PermissionService permissionService;

    /**
     * @param userService       用户服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验用户查询权限）
     */
    public GetUserTool(UserService userService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查询用户");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_USER_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「用户查询」权限，已拒绝查询用户");
        }
        Long userId = AiToolSupport.parseId(AiToolSupport.input(param).get("user_id"));
        if (userId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "用户 ID 不能为空且必须是数字");
        }
        try {
            UserDetailDTO user = userService.selectUserDetailByAi(userId, operatorId);
            if (user == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "未找到用户（user_id=" + userId + "），可能不存在或已被删除。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(user));
        } catch (Exception e) {
            log.error("查询用户详情失败：userId={}", userId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询用户失败：" + e.getMessage());
        }
    }

    /**
     * 渲染用户详情文本。
     *
     * @param user 用户详情
     * @return 文本
     */
    private static String render(UserDetailDTO user) {
        String roleNames = user.getRoleNames() == null || user.getRoleNames().isEmpty()
            ? "（未分配角色）" : String.join("、", user.getRoleNames());
        return "用户详情：\n"
            + "- 用户 ID：" + user.getUserId() + "\n"
            + "- 用户账号：" + AiToolSupport.display(user.getUserName()) + "\n"
            + "- 用户昵称：" + AiToolSupport.display(user.getNickName()) + "\n"
            + "- 所属部门：" + AiToolSupport.display(user.getDeptName())
            + "（dept_id=" + AiToolSupport.display(user.getDeptId()) + "）\n"
            + "- 手机号码：" + AiToolSupport.display(user.getPhoneNumber()) + "\n"
            + "- 邮箱：" + AiToolSupport.display(user.getEmail()) + "\n"
            + "- 性别：" + AiToolSupport.genderText(user.getGender()) + "\n"
            + "- 账号状态：" + AiToolSupport.statusText(user.getStatus()) + "\n"
            + "- 用户类型：" + AiToolSupport.display(user.getUserType()) + "\n"
            + "- 角色：" + roleNames + "\n"
            + "- 最后登录时间：" + AiToolSupport.display(user.getLoginDate()) + "\n"
            + "- 创建时间：" + AiToolSupport.display(user.getCreateTime());
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("user_id", Map.of("type", "string", "description", "用户 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("user_id"));
        return schema;
    }

}