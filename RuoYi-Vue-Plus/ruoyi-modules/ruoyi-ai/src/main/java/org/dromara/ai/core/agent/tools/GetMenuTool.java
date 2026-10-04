package org.dromara.ai.core.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.domain.MenuDetailDTO;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看菜单权限详情工具（只读）。
 *
 * <p>按菜单 ID 查询菜单的名称、父菜单、类型、路由、权限标识与状态；
 * 需要「某权限标识属于哪个菜单」「某按钮的权限标识是什么」等事实时调用，不要凭猜测回答。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public class GetMenuTool extends ToolBase {

    /**
     * 工具名称（须与 {@code ai_tool.tool_code} 的种子行一致）
     */
    public static final String TOOL_NAME = "get_menu";

    /**
     * 工具描述（模型据此判断何时调用）
     */
    private static final String DESCRIPTION = "按菜单 ID 查询菜单权限的详细信息（名称、父菜单 ID、显示顺序、路由地址、"
        + "组件路径、类型、显示状态、状态、权限标识、备注）。属于只读操作。菜单 ID 可从「搜索菜单权限」结果中获取。";

    /**
     * 查看菜单所需权限
     */
    private static final String PERM_MENU_QUERY = "system:menu:query";

    private final MenuService menuService;

    private final PermissionService permissionService;

    /**
     * @param menuService       菜单服务（跨模块只读查询）
     * @param permissionService 权限服务（按用户校验菜单查询权限）
     */
    public GetMenuTool(MenuService menuService, PermissionService permissionService) {
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
            return AiToolSupport.fail(param, TOOL_NAME, "无法识别当前操作人，已拒绝查询菜单权限");
        }
        if (!AiToolSupport.hasPermission(permissionService, operatorId, PERM_MENU_QUERY)) {
            return AiToolSupport.fail(param, TOOL_NAME, "当前用户无「菜单查询」权限，已拒绝查询菜单权限");
        }
        Long menuId = AiToolSupport.parseId(AiToolSupport.input(param).get("menu_id"));
        if (menuId == null) {
            return AiToolSupport.fail(param, TOOL_NAME, "菜单 ID 不能为空且必须是数字");
        }
        try {
            MenuDetailDTO menu = menuService.selectMenuDetailByAi(menuId);
            if (menu == null) {
                return AiToolSupport.ok(param, TOOL_NAME,
                    "未找到菜单权限（menu_id=" + menuId + "），可能不存在或已被删除。");
            }
            return AiToolSupport.ok(param, TOOL_NAME, render(menu));
        } catch (Exception e) {
            log.error("查询菜单权限详情失败：menuId={}", menuId, e);
            return AiToolSupport.fail(param, TOOL_NAME, "查询菜单权限失败：" + e.getMessage());
        }
    }

    /**
     * 渲染菜单权限详情文本。
     *
     * @param menu 菜单详情
     * @return 文本
     */
    private static String render(MenuDetailDTO menu) {
        StringBuilder sb = new StringBuilder("菜单权限详情：\n");
        sb.append("- 菜单 ID：").append(menu.getMenuId()).append("\n");
        sb.append("- 菜单名称：").append(AiToolSupport.display(menu.getMenuName())).append("\n");
        sb.append("- 父菜单 ID：").append(AiToolSupport.display(menu.getParentId())).append("\n");
        sb.append("- 类型：").append(AiToolSupport.menuTypeText(menu.getMenuType())).append("\n");
        sb.append("- 权限标识：").append(AiToolSupport.display(menu.getPerms())).append("\n");
        sb.append("- 显示顺序：").append(AiToolSupport.display(menu.getOrderNum())).append("\n");
        if (isNotButton(menu.getMenuType())) {
            sb.append("- 路由地址：").append(AiToolSupport.display(menu.getPath())).append("\n");
            sb.append("- 组件路径：").append(AiToolSupport.display(menu.getComponent())).append("\n");
            sb.append("- 显示状态：").append(visibleText(menu.getVisible())).append("\n");
        }
        sb.append("- 状态：").append(AiToolSupport.statusText(menu.getStatus())).append("\n");
        sb.append("- 备注：").append(AiToolSupport.display(menu.getRemark()));
        return sb.toString();
    }

    /**
     * 判断是否为按钮类型（按钮无路由与显示状态）。
     *
     * @param menuType 菜单类型
     * @return 是否按钮
     */
    private static boolean isNotButton(String menuType) {
        return !"F".equals(menuType);
    }

    /**
     * 显示状态文案（0显示 1隐藏）。
     *
     * @param visible 显示状态值
     * @return 展示文案
     */
    private static String visibleText(String visible) {
        if ("0".equals(visible)) {
            return "显示";
        }
        if ("1".equals(visible)) {
            return "隐藏";
        }
        return AiToolSupport.display(visible);
    }

    /**
     * 构造参数 JSON Schema。
     *
     * @return JSON Schema
     */
    private static Map<String, Object> buildSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("menu_id", Map.of("type", "string", "description", "菜单 ID（数字字符串）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("menu_id"));
        return schema;
    }

}