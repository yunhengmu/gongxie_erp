package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.domain.MenuDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索菜单权限工具（只读）。
 *
 * <p>按菜单名称或权限标识关键词模糊检索（可附状态筛选），返回菜单 ID、名称、类型、权限标识与状态，
 * 用于回答「权限标识是什么」或为「查看权限详情 / 查询角色权限」取得菜单 ID。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class SearchMenusTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "search_menus";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按关键词搜索菜单权限（对菜单名称与权限标识模糊匹配），可附带状态筛选。"
        + "返回菜单 ID、名称、类型（目录 / 菜单 / 按钮）、权限标识与状态，用于了解系统权限或取得菜单 ID。"
        + "属于只读操作。关键词留空则列出全部菜单，结果较多时建议补充分组关键词。";

    /**
     * 搜索菜单所需权限
     */
    private static final String PERM_MENU_LIST = "system:menu:list";

    /**
     * 单次返回的菜单条数上限（超出只回前 N 条并提示缩小范围）
     */
    private static final int MAX_RESULT = 50;

    private final MenuService menuService;

    private final PermissionService permissionService;

    /**
     * @param menuService       菜单服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验菜单列表权限）
     */
    public SearchMenusTool(MenuService menuService, PermissionService permissionService) {
        super(ToolBase.builder()
            .name(TOOL_NAME)
            .description(DESCRIPTION)
            .inputSchema(buildSchema())
            .readOnly(true)
            .concurrencySafe(true));
        this.menuService = menuService;
        this.permissionService = permissionService;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Long operatorId = AiToolSupport.operatorId(param);
        if (operatorId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝搜索菜单权限");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_MENU_LIST)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「菜单列表」权限，已拒绝搜索菜单权限");
        }
        Map<String, Object> input = AiToolSupport.input(param);
        String keyword = StringUtils.trim(AiToolSupport.stringValue(input.get("keyword")));
        String perms = StringUtils.trim(AiToolSupport.stringValue(input.get("perms")));
        String status = StringUtils.trim(AiToolSupport.stringValue(input.get("status")));
        if (!StringUtils.isBlank(status) && !"0".equals(status) && !"1".equals(status)) {
            return AiToolSupport.fail(param, TOOL_NAME, "状态只能是 0（正常）或 1（停用）");
        }
        try {
            List<MenuDetailDTO> menus = menuService.searchMenusByAi(keyword, perms, status);
            return AiToolSupport.ok(param, TOOL_NAME, render(menus, keyword, perms, status));
        } catch (Exception e) {
            log.error("搜索菜单权限失败：keyword={}, perms={}, status={}", keyword, perms, status, e);
            return AiToolSupport.fail(param, TOOL_NAME, "搜索菜单权限失败：" + e.getMessage());
        }
    }

    /**
     * 渲染搜索结果文本。
     *
     * @param menus   菜单列表
     * @param keyword 菜单名称关键词
     * @param perms   权限标识关键词
     * @param status  状态筛选
     * @return 文本
     */
    private static String render(List<MenuDetailDTO> menus, String keyword, String perms, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(menus.size()).append(" 个菜单权限");
        if (StringUtils.isNotBlank(keyword)) {
            sb.append("（名称关键词：").append(keyword).append("）");
        }
        if (StringUtils.isNotBlank(perms)) {
            sb.append("（权限标识关键词：").append(perms).append("）");
        }
        if (StringUtils.isNotBlank(status)) {
            sb.append("（状态：").append(AiToolSupport.statusText(status)).append("）");
        }
        if (menus.isEmpty()) {
            sb.append("：没有匹配的菜单权限。");
            return sb.toString();
        }
        sb.append("：\n");
        int limit = Math.min(menus.size(), MAX_RESULT);
        for (int i = 0; i < limit; i++) {
            MenuDetailDTO menu = menus.get(i);
            sb.append("- menu_id=").append(menu.getMenuId())
                .append(" | ").append(AiToolSupport.display(menu.getMenuName()))
                .append(" | 类型=").append(AiToolSupport.menuTypeText(menu.getMenuType()))
                .append(" | 权限标识=").append(AiToolSupport.display(menu.getPerms()))
                .append("（parent_id=").append(AiToolSupport.display(menu.getParentId())).append("）")
                .append(" | 状态=").append(AiToolSupport.statusText(menu.getStatus()))
                .append("\n");
        }
        if (menus.size() > limit) {
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
        properties.put("keyword", Map.of("type", "string", "description", "菜单名称关键词（模糊匹配），留空不限"));
        properties.put("perms", Map.of("type", "string",
            "description", "权限标识关键词（模糊匹配），如 system:role，留空不限"));
        properties.put("status", Map.of("type", "string", "enum", List.of("0", "1"),
            "description", "菜单状态：0 正常 / 1 停用，留空不限"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

}