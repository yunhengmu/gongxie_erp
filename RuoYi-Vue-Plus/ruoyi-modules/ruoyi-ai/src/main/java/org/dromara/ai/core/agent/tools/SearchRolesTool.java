package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.domain.RoleDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索角色工具（只读）。
 *
 * <p>按角色名称或权限字符关键词模糊检索（可附状态筛选），返回角色 ID、名称、权限字符、
 * 数据范围与状态，用于回答权限体系问题或为「查看角色详情 / 查询角色权限」取得角色 ID。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class SearchRolesTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "search_roles";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按关键词搜索角色（对角色名称与权限字符模糊匹配），可附带状态筛选。"
        + "返回角色 ID、名称、权限字符、数据范围与状态，用于了解权限体系或取得角色 ID。属于只读操作。"
        + "关键词留空则列出全部角色。";

    /**
     * 搜索角色所需权限
     */
    private static final String PERM_ROLE_LIST = "system:role:list";

    /**
     * 单次返回的角色条数上限（超出只回前 N 条并提示缩小范围）
     */
    private static final int MAX_RESULT = 50;

    private final RoleService roleService;

    private final PermissionService permissionService;

    /**
     * @param roleService       角色服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验角色列表权限）
     */
    public SearchRolesTool(RoleService roleService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝搜索角色");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_ROLE_LIST)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「角色列表」权限，已拒绝搜索角色");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = StringUtils.trim(AiToolSupport.stringValue(input.get("keyword")));
        String status = StringUtils.trim(AiToolSupport.stringValue(input.get("status")));
        if (!StringUtils.isBlank(status) && !"0".equals(status) && !"1".equals(status)) {
            return AiToolSupport.fail(param, TOOL_NAME, "状态只能是 0（正常）或 1（停用）");
        }
        try {
            List<RoleDetailDTO> roles = roleService.searchRolesByAi(keyword, status);
            return AiToolSupport.ok(param, TOOL_NAME, render(roles, keyword, status));
        } catch (Exception e) {
            log.error("搜索角色失败：keyword={}, status={}", keyword, status, e);
            return AiToolSupport.fail(param, TOOL_NAME, "搜索角色失败：" + e.getMessage());
        }
    }

    /**
     * 渲染搜索结果文本。
     *
     * @param roles   角色列表
     * @param keyword 关键词
     * @param status  状态筛选
     * @return 文本
     */
    private static String render(List<RoleDetailDTO> roles, String keyword, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(roles.size()).append(" 个角色");
        if (StringUtils.isNotBlank(keyword)) {
            sb.append("（关键词：").append(keyword).append("）");
        }
        if (StringUtils.isNotBlank(status)) {
            sb.append("（状态：").append(AiToolSupport.statusText(status)).append("）");
        }
        if (roles.isEmpty()) {
            sb.append("：没有匹配的角色。");
            return sb.toString();
        }
        sb.append("：\n");
        int limit = Math.min(roles.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            RoleDetailDTO role = roles.get(i);
            sb.append("- role_id=").append(role.getRoleId())
                .append(" | ").append(AiToolSupport.display(role.getRoleName()))
                .append(" | 权限字符=").append(AiToolSupport.display(role.getRoleKey()))
                .append(" | 数据范围=").append(AiToolSupport.dataScopeText(role.getDataScope()))
                .append(" | 状态=").append(AiToolSupport.statusText(role.getStatus()))
                .append("\n");
        }
        if (roles.size() > limit) {
            sb.append("（仅列出前 ").append(limit).append(" 条，请用更精确的关键词缩小范围）\n");
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
        properties.put("keyword", Map.of("type", "string",
            "description", "角色名称或权限字符关键词（模糊匹配），留空列出全部"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "角色状态：0 正常 / 1 停用，留空不限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

}