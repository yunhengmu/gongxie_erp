package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 获取当前用户信息工具（只读、无入参）。
 *
 * <p>返回**当前提问用户本人**的账号信息（用户 ID / 账号 / 昵称 / 所属部门 ID / 手机号 / 邮箱 / 性别 /
 * 账号状态 / 创建时间）与是否超级管理员。工具**没有参数**，因此不存在"查别人"的越权面——
 * 这正是它可以列入 {@code AgentRegistry.BUILTIN_READONLY_TOOL_CODES}
 * （无需逐智能体绑定即装配、未绑定时默认放行）的前提。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetCurrentUserTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_current_user";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "获取当前提问用户本人的账号信息（用户 ID、账号、昵称、所属部门 ID、"
        + "用户类型、手机号、邮箱、性别、账号状态、创建时间）以及是否为超级管理员。"
        + "当问题涉及「我是谁」「我的账号 / 联系方式 / 所属部门」时调用，不要凭猜测回答。"
        + "只读操作，无入参，只返回提问用户本人的信息。";

    private final UserService userService;

    /**
     * @param userService 通用用户服务（跨模块只读查询）
     */
    public GetCurrentUserTool(UserService userService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.userService = userService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            // 出口（MCP）等场景没有平台用户身份，明确拒绝而不是返回空信息
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前提问用户的平台身份，已拒绝查询用户信息");
        }
        try {
            UserDTO user = userService.selectById(operatorId);
            if (user == null) {
                return AiToolSupport.fail(param, TOOL_NAME, "未查到当前用户信息（该用户可能已被删除或停用）");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(user));
        } catch (Exception e) {
            log.error("查询当前用户信息失败：userId={}", operatorId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询当前用户信息失败：" + e.getMessage());
        }
    }

    /**
     * 渲染用户信息文本。
     *
     * @param user 用户信息
     * @return 文本
     */
    private static String render(UserDTO user) {
        boolean superAdmin = SystemConstants.SUPER_ADMIN_USER_ID.equals(user.getUserId());
        return "当前提问用户的账号信息：\n"
            + "- 用户 ID：" + user.getUserId() + "\n"
            + "- 用户账号：" + AiToolSupport.display(user.getUserName()) + "\n"
            + "- 用户昵称：" + AiToolSupport.display(user.getNickName()) + "\n"
            + "- 所属部门 ID：" + AiToolSupport.display(user.getDeptId()) + "\n"
            + "- 用户类型：" + AiToolSupport.display(user.getUserType()) + "\n"
            + "- 手机号码：" + AiToolSupport.display(user.getPhoneNumber()) + "\n"
            + "- 邮箱：" + AiToolSupport.display(user.getEmail()) + "\n"
            + "- 性别：" + genderText(user.getGender()) + "\n"
            + "- 账号状态：" + AiToolSupport.statusText(user.getStatus()) + "\n"
            + "- 是否超级管理员：" + (superAdmin ? "是" : "否") + "\n"
            + "- 创建时间：" + AiToolSupport.display(user.getCreateTime()) + "\n"
            + "说明：以上为提问用户本人的账号信息。";
    }

    /**
     * 性别展示文案（0男 1女 2未知）。
     *
     * @param gender 性别值
     * @return 展示文案
     */
    private static String genderText(String gender) {
        if ("0".equals(gender)) {
            return "男";
        }
        if ("1".equals(gender)) {
            return "女";
        }
        if ("2".equals(gender)) {
            return "未知";
        }
        return AiToolSupport.display(gender);
    }

    /**
     * 构造参数 JSON Schema（本工具无入参）。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

}
