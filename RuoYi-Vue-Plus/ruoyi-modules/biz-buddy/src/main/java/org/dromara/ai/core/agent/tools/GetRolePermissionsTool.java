package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.domain.RoleDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询角色权限对应关系工具（只读）。
 *
 * <p>按角色 ID 反查该角色已分配的权限标识（菜单权限）集合，
 * 用于回答「某角色有哪些权限」「某角色能不能做某事」这类问题，事实一律来自平台数据。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetRolePermissionsTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_role_permissions";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按角色 ID 查询该角色已分配的权限标识（菜单权限）清单，"
        + "同时返回角色名称与权限字符。用于回答「某角色拥有哪些权限」等问题。属于只读操作。"
        + "角色 ID 可从「搜索角色」结果中获取。";

    /**
     * 查询角色权限所需权限
     */
    private static final String PERM_ROLE_QUERY = "system:role:query";

    /**
     * 单次返回的权限标识条数上限（超出只回前 N 条）
     */
    private static final int MAX_RESULT = 200;

    private final RoleService roleService;

    private final MenuService menuService;

    private final PermissionService permissionService;

    /**
     * @param roleService       角色服务（跨模块只读查询，取角色名称）
     * @param menuService       菜单服务（跨模块只读查询，取角色已分配的权限标识）
     * @param permissionService 权限服务（按用户校验角色查询权限）
     */
    public GetRolePermissionsTool(RoleService roleService, MenuService menuService,
                                  PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.roleService = roleService;
        this.menuService = menuService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查询角色权限");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_ROLE_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「角色查询」权限，已拒绝查询角色权限");
        }
        Long roleId = AiToolSupport.parseId(AiToolSupport.input(param).get("role_id"));
        if (roleId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "角色 ID 不能为空且必须是数字");
        }
        try {
            RoleDetailDTO role = roleService.selectRoleDetailByAi(roleId);
            if (role == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "未找到角色（role_id=" + roleId + "），可能不存在或已被删除。");
            }
            List<String> perms = menuService.selectRolePermsByAi(roleId);
            return AiToolSupport.ok(param, TOOL_NAME, render(role, perms));
        } catch (Exception e) {
            log.error("查询角色权限失败：roleId={}", roleId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询角色权限失败：" + e.getMessage());
        }
    }

    /**
     * 渲染角色权限文本。
     *
     * @param role  角色详情
     * @param perms 权限标识列表
     * @return 文本
     */
    private static String render(RoleDetailDTO role, List<String> perms) {
        StringBuilder sb = new StringBuilder();
        sb.append("角色「").append(AiToolSupport.display(role.getRoleName()))
            .append("」（role_id=").append(role.getRoleId())
            .append("，权限字符=").append(AiToolSupport.display(role.getRoleKey()))
            .append("）");
        if (perms.isEmpty()) {
            sb.append("尚未分配任何权限标识。");
            return sb.toString();
        }
        sb.append("共分配 ").append(perms.size()).append(" 个权限标识：\n");
        int limit = Math.min(perms.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            sb.append("- ").append(perms.get(i)).append("\n");
        }
        if (perms.size() > limit) {
            sb.append("（仅列出前 ").append(limit).append(" 条）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("role_id", Map.of("type", "string", "description", "角色 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("role_id"));
        return schema;
    }

}