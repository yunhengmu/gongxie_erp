package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.domain.RoleDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看角色详情工具（只读）。
 *
 * <p>按角色 ID 查询角色的名称、权限字符、显示顺序、数据范围与状态；
 * 需要「某角色是否为全部数据权限」「某角色的权限字符是什么」等事实时调用，不要凭猜测回答。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetRoleTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_role";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按角色 ID 查询角色的详细信息（名称、权限字符、显示顺序、数据范围、"
        + "状态、备注、创建时间）。属于只读操作。角色 ID 可从「搜索角色」结果中获取。";

    /**
     * 查看角色所需权限
     */
    private static final String PERM_ROLE_QUERY = "system:role:query";

    private final RoleService roleService;

    private final PermissionService permissionService;

    /**
     * @param roleService       角色服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验角色查询权限）
     */
    public GetRoleTool(RoleService roleService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查询角色");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_ROLE_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「角色查询」权限，已拒绝查询角色");
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
            return AiToolSupport.ok(param, TOOL_NAME, render(role));
        } catch (Exception e) {
            log.error("查询角色详情失败：roleId={}", roleId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询角色失败：" + e.getMessage());
        }
    }

    /**
     * 渲染角色详情文本。
     *
     * @param role 角色详情
     * @return 文本
     */
    private static String render(RoleDetailDTO role) {
        return "角色详情：\n"
            + "- 角色 ID：" + role.getRoleId() + "\n"
            + "- 角色名称：" + AiToolSupport.display(role.getRoleName()) + "\n"
            + "- 权限字符：" + AiToolSupport.display(role.getRoleKey()) + "\n"
            + "- 显示顺序：" + AiToolSupport.display(role.getRoleSort()) + "\n"
            + "- 数据范围：" + AiToolSupport.dataScopeText(role.getDataScope()) + "\n"
            + "- 状态：" + AiToolSupport.statusText(role.getStatus()) + "\n"
            + "- 备注：" + AiToolSupport.display(role.getRemark()) + "\n"
            + "- 创建时间：" + AiToolSupport.display(role.getCreateTime());
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