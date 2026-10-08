package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.service.PermissionService;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 获取当前用户权限信息工具（只读、无入参）。
 *
 * <p>返回**当前提问用户本人**的角色标识与菜单权限标识（以及是否超级管理员），供模型判断
 * 「该用户能不能做某件事」——例如用户问「我能发布公告吗」，模型可据此判断自己是否应调用
 * {@code create_notice}。工具**没有参数**，无法查询他人权限，无越权面，
 * 可列入 {@code AgentRegistry.BUILTIN_READONLY_TOOL_CODES}（无需逐智能体绑定即装配、未绑定时默认放行）。</p>
 *
 * <p>权限清单较长时按上限截断（角色 50 / 菜单权限 200），并给出总数，避免把上下文撑爆。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetCurrentPermissionsTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_current_permissions";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "获取当前提问用户本人被授予的角色与功能权限（角色标识、菜单权限标识，"
        + "以及是否为超级管理员）。当需要判断「该用户是否有权做某件事」或用户直接询问自己的权限时调用，"
        + "不要凭猜测回答。只读操作，无入参，只返回提问用户本人的权限，不能查询他人权限。";

    /**
     * 角色标识展示上限
     */
    private static final int ROLE_LIMIT = 50;

    /**
     * 菜单权限展示上限
     */
    private static final int PERM_LIMIT = 200;

    private final PermissionService permissionService;

    /**
     * @param permissionService 权限服务（不依赖线程上下文，可安全用于 Reactor 线程）
     */
    public GetCurrentPermissionsTool(PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前提问用户的平台身份，已拒绝查询权限信息");
        }
        try {
            Set<String> roles = permissionService.getRolePermission(operatorId);
            Set<String> perms = permissionService.getMenuPermission(operatorId);
            boolean superAdmin = SystemConstants.SUPER_ADMIN_USER_ID.equals(operatorId);
            return AiToolSupport.ok(param, TOOL_NAME, render(superAdmin, roles, perms));
        } catch (Exception e) {
            log.error("查询当前用户权限失败：userId={}", operatorId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询当前用户权限失败：" + e.getMessage());
        }
    }

    /**
     * 渲染权限信息文本。
     *
     * @param superAdmin 是否超级管理员
     * @param roles      角色标识集合
     * @param perms      菜单权限标识集合
     * @return 文本
     */
    private static String render(boolean superAdmin, Set<String> roles, Set<String> perms) {
        List<String> roleList = sorted(roles);
        List<String> permList = sorted(perms);
        StringBuilder sb = new StringBuilder("当前提问用户的权限信息：\n");
        sb.append("- 是否超级管理员：").append(superAdmin ? "是（拥有平台全部权限）" : "否").append("\n");
        sb.append("- 角色标识（").append(roleList.size()).append(" 个）：")
            .append(joinLimited(roleList, ROLE_LIMIT)).append("\n");
        sb.append("- 菜单权限（").append(permList.size()).append(" 个）：")
            .append(joinLimited(permList, PERM_LIMIT)).append("\n");
        if (permList.contains(AiToolSupport.PERM_ALL)) {
            sb.append("  说明：出现 ").append(AiToolSupport.PERM_ALL)
                .append(" 表示该用户拥有通配权限，即平台全部功能权限。\n");
        }
        sb.append("说明：以上为提问用户**本人**被授予的角色与功能权限（不能查询他人权限）；"
            + "工具与接口在真正执行时仍会按操作人二次校验权限，故「有权限」不等于「操作一定成功」。");
        return sb.toString();
    }

    /**
     * 集合转有序去重列表（按字典序，便于阅读与稳定输出）。
     *
     * @param values 原始集合
     * @return 有序列表
     */
    private static List<String> sorted(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(values).stream().sorted().toList();
    }

    /**
     * 按上限拼接展示（超限时追加省略说明）。
     *
     * @param values 值列表
     * @param limit  展示上限
     * @return 展示文本
     */
    private static String joinLimited(List<String> values, int limit) {
        if (values.isEmpty()) {
            return "（无）";
        }
        String text = String.join(", ", values.subList(0, Math.min(values.size(), limit)));
        return values.size() > limit ? text + " ……（仅列出前 " + limit + " 条）" : text;
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
