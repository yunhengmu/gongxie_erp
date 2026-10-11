package org.dromara.ai.core.agent;

import io.agentscope.core.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.tools.CreateDeptTool;
import org.dromara.ai.core.agent.tools.CreateNoticeTool;
import org.dromara.ai.core.agent.tools.CreateUserTool;
import org.dromara.ai.core.agent.tools.DeleteDeptTool;
import org.dromara.ai.core.agent.tools.DeleteNoticeTool;
import org.dromara.ai.core.agent.tools.DeleteUserTool;
import org.dromara.ai.core.agent.tools.GetCurrentDeptTool;
import org.dromara.ai.core.agent.tools.GetCurrentPermissionsTool;
import org.dromara.ai.core.agent.tools.GetCurrentTimeTool;
import org.dromara.ai.core.agent.tools.GetCurrentUserTool;
import org.dromara.ai.core.agent.tools.GetDeptTool;
import org.dromara.ai.core.agent.tools.GetMenuTool;
import org.dromara.ai.core.agent.tools.GetNoticeTool;
import org.dromara.ai.core.agent.tools.GetRolePermissionsTool;
import org.dromara.ai.core.agent.tools.GetRoleTool;
import org.dromara.ai.core.agent.tools.GetSystemInfoTool;
import org.dromara.ai.core.agent.tools.GetUserTool;
import org.dromara.ai.core.agent.tools.KnowledgeSearchTool;
import org.dromara.ai.core.agent.tools.SearchDeptTool;
import org.dromara.ai.core.agent.tools.SearchMenusTool;
import org.dromara.ai.core.agent.tools.SearchNoticeTool;
import org.dromara.ai.core.agent.tools.SearchRolesTool;
import org.dromara.ai.core.agent.tools.SearchUserTool;
import org.dromara.ai.core.agent.tools.UpdateDeptTool;
import org.dromara.ai.core.agent.tools.UpdateNoticeTool;
import org.dromara.ai.core.agent.tools.UpdateUserTool;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.service.PermissionService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 业务工具工厂：把 {@code ai_tool.impl_name} 映射为代码侧已实现的 {@link AgentTool} 实例。
 *
 * <p>新增工具的正确姿势是：在代码中实现 {@link AgentTool} → 在此登记 implName → 到「业务工具注册」页登记元数据。
 * 注册页本身不产生任何可执行能力，避免出现裸 SQL / 裸 Shell / 裸 HTTP 工具。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolFactory {

    /**
     * 知识库检索实现标识（只读）
     */
    public static final String IMPL_KNOWLEDGE_SEARCH = "knowledge_search";

    /**
     * 发布系统公告实现标识（写入，需人工确认）
     */
    public static final String IMPL_CREATE_NOTICE = "create_notice";

    /**
     * 搜索公告实现标识（只读）
     */
    public static final String IMPL_SEARCH_NOTICE = "search_notice";

    /**
     * 查看公告详情实现标识（只读）
     */
    public static final String IMPL_GET_NOTICE = "get_notice";

    /**
     * 修改公告实现标识（写入，需人工确认）
     */
    public static final String IMPL_UPDATE_NOTICE = "update_notice";

    /**
     * 删除公告实现标识（写入，需人工确认）
     */
    public static final String IMPL_DELETE_NOTICE = "delete_notice";

    /**
     * 获取当前时间实现标识（平台内置无害只读工具，无参数）
     */
    public static final String IMPL_GET_CURRENT_TIME = "get_current_time";

    /**
     * 查看部门详情实现标识（只读）
     */
    public static final String IMPL_GET_DEPT = "get_dept";

    /**
     * 搜索部门实现标识（只读）
     */
    public static final String IMPL_SEARCH_DEPT = "search_dept";

    /**
     * 新增部门实现标识（写入，需人工确认）
     */
    public static final String IMPL_CREATE_DEPT = "create_dept";

    /**
     * 修改部门实现标识（写入，需人工确认）
     */
    public static final String IMPL_UPDATE_DEPT = "update_dept";

    /**
     * 删除部门实现标识（写入，需人工确认）
     */
    public static final String IMPL_DELETE_DEPT = "delete_dept";

    /**
     * 获取当前用户信息实现标识（平台内置无害只读工具，无参数）
     */
    public static final String IMPL_GET_CURRENT_USER = "get_current_user";

    /**
     * 获取当前用户所属部门信息实现标识（平台内置无害只读工具，无参数）
     */
    public static final String IMPL_GET_CURRENT_DEPT = "get_current_dept";

    /**
     * 获取当前用户权限信息实现标识（平台内置无害只读工具，无参数）
     */
    public static final String IMPL_GET_CURRENT_PERMISSIONS = "get_current_permissions";

    /**
     * 查看用户详情实现标识（只读）
     */
    public static final String IMPL_GET_USER = "get_user";

    /**
     * 搜索用户实现标识（只读）
     */
    public static final String IMPL_SEARCH_USER = "search_user";

    /**
     * 新增用户实现标识（写入，需人工确认）
     */
    public static final String IMPL_CREATE_USER = "create_user";

    /**
     * 修改用户实现标识（写入，需人工确认）
     */
    public static final String IMPL_UPDATE_USER = "update_user";

    /**
     * 删除用户实现标识（写入，需人工确认）
     */
    public static final String IMPL_DELETE_USER = "delete_user";

    /**
     * 搜索角色实现标识（只读）
     */
    public static final String IMPL_SEARCH_ROLES = "search_roles";

    /**
     * 查看角色详情实现标识（只读）
     */
    public static final String IMPL_GET_ROLE = "get_role";

    /**
     * 搜索菜单权限实现标识（只读）
     */
    public static final String IMPL_SEARCH_MENUS = "search_menus";

    /**
     * 查看菜单权限详情实现标识（只读）
     */
    public static final String IMPL_GET_MENU = "get_menu";

    /**
     * 查询角色权限对应关系实现标识（只读）
     */
    public static final String IMPL_GET_ROLE_PERMISSIONS = "get_role_permissions";

    /**
     * 获取系统信息实现标识（平台内置无害只读工具，无参数；MCP 服务端/客户端联动演示用）
     */
    public static final String IMPL_GET_SYSTEM_INFO = "get_system_info";

    private final IAiVectorService vectorService;

    private final IAiKnowledgeBaseService knowledgeBaseService;

    private final NoticeService noticeService;

    private final PermissionService permissionService;

    private final AiProperties aiProperties;

    private final DeptService deptService;

    private final UserService userService;

    private final RoleService roleService;

    private final MenuService menuService;

    /**
     * 判断实现标识是否已在代码侧登记。
     *
     * @param implName 实现标识
     * @return 是否已登记
     */
    public boolean isSupported(String implName) {
        return IMPL_KNOWLEDGE_SEARCH.equals(implName)
            || IMPL_CREATE_NOTICE.equals(implName)
            || IMPL_SEARCH_NOTICE.equals(implName)
            || IMPL_GET_NOTICE.equals(implName)
            || IMPL_UPDATE_NOTICE.equals(implName)
            || IMPL_DELETE_NOTICE.equals(implName)
            || IMPL_GET_CURRENT_TIME.equals(implName)
            || IMPL_GET_DEPT.equals(implName)
            || IMPL_SEARCH_DEPT.equals(implName)
            || IMPL_CREATE_DEPT.equals(implName)
            || IMPL_UPDATE_DEPT.equals(implName)
            || IMPL_DELETE_DEPT.equals(implName)
            || IMPL_GET_CURRENT_USER.equals(implName)
            || IMPL_GET_CURRENT_DEPT.equals(implName)
            || IMPL_GET_CURRENT_PERMISSIONS.equals(implName)
            || IMPL_GET_USER.equals(implName)
            || IMPL_SEARCH_USER.equals(implName)
            || IMPL_CREATE_USER.equals(implName)
            || IMPL_UPDATE_USER.equals(implName)
            || IMPL_DELETE_USER.equals(implName)
            || IMPL_SEARCH_ROLES.equals(implName)
            || IMPL_GET_ROLE.equals(implName)
            || IMPL_SEARCH_MENUS.equals(implName)
            || IMPL_GET_MENU.equals(implName)
            || IMPL_GET_ROLE_PERMISSIONS.equals(implName)
            || IMPL_GET_SYSTEM_INFO.equals(implName);
    }

    /**
     * 构造工具实例（智能体装配场景）。
     *
     * @param definition 工具注册定义
     * @param kbIds      智能体绑定的知识库主键集合（供检索类工具使用）
     * @return 工具实例；实现未登记时返回空
     */
    public Optional<AgentTool> create(AiTool definition, List<Long> kbIds) {
        return create(definition, kbIds, false);
    }

    /**
     * 构造工具实例。
     *
     * @param definition    工具注册定义
     * @param kbIds         知识库主键集合（供检索类工具使用）
     * @param platformScope 是否平台出口（MCP）场景：检索类工具不再按提问用户过滤生效范围
     * @return 工具实例；实现未登记时返回空
     */
    public Optional<AgentTool> create(AiTool definition, List<Long> kbIds, boolean platformScope) {
        String implName = definition.getImplName();
        if (IMPL_KNOWLEDGE_SEARCH.equals(implName)) {
            return Optional.of(new KnowledgeSearchTool(vectorService, kbIds, null, knowledgeBaseService, platformScope));
        }
        if (IMPL_CREATE_NOTICE.equals(implName)) {
            return Optional.of(new CreateNoticeTool(noticeService, permissionService));
        }
        if (IMPL_SEARCH_NOTICE.equals(implName)) {
            return Optional.of(new SearchNoticeTool(noticeService, permissionService));
        }
        if (IMPL_GET_NOTICE.equals(implName)) {
            return Optional.of(new GetNoticeTool(noticeService, permissionService));
        }
        if (IMPL_UPDATE_NOTICE.equals(implName)) {
            return Optional.of(new UpdateNoticeTool(noticeService, permissionService));
        }
        if (IMPL_DELETE_NOTICE.equals(implName)) {
            return Optional.of(new DeleteNoticeTool(noticeService, permissionService));
        }
        if (IMPL_GET_CURRENT_TIME.equals(implName)) {
            return Optional.of(new GetCurrentTimeTool(aiProperties));
        }
        if (IMPL_GET_DEPT.equals(implName)) {
            return Optional.of(new GetDeptTool(deptService, permissionService));
        }
        if (IMPL_SEARCH_DEPT.equals(implName)) {
            return Optional.of(new SearchDeptTool(deptService, permissionService));
        }
        if (IMPL_CREATE_DEPT.equals(implName)) {
            return Optional.of(new CreateDeptTool(deptService, permissionService));
        }
        if (IMPL_UPDATE_DEPT.equals(implName)) {
            return Optional.of(new UpdateDeptTool(deptService, permissionService));
        }
        if (IMPL_DELETE_DEPT.equals(implName)) {
            return Optional.of(new DeleteDeptTool(deptService, permissionService));
        }
        if (IMPL_GET_CURRENT_USER.equals(implName)) {
            return Optional.of(new GetCurrentUserTool(userService));
        }
        if (IMPL_GET_CURRENT_DEPT.equals(implName)) {
            return Optional.of(new GetCurrentDeptTool(userService, deptService));
        }
        if (IMPL_GET_CURRENT_PERMISSIONS.equals(implName)) {
            return Optional.of(new GetCurrentPermissionsTool(permissionService));
        }
        if (IMPL_GET_USER.equals(implName)) {
            return Optional.of(new GetUserTool(userService, permissionService));
        }
        if (IMPL_SEARCH_USER.equals(implName)) {
            return Optional.of(new SearchUserTool(userService, permissionService));
        }
        if (IMPL_CREATE_USER.equals(implName)) {
            return Optional.of(new CreateUserTool(userService, permissionService));
        }
        if (IMPL_UPDATE_USER.equals(implName)) {
            return Optional.of(new UpdateUserTool(userService, permissionService));
        }
        if (IMPL_DELETE_USER.equals(implName)) {
            return Optional.of(new DeleteUserTool(userService, permissionService));
        }
        if (IMPL_SEARCH_ROLES.equals(implName)) {
            return Optional.of(new SearchRolesTool(roleService, permissionService));
        }
        if (IMPL_GET_ROLE.equals(implName)) {
            return Optional.of(new GetRoleTool(roleService, permissionService));
        }
        if (IMPL_SEARCH_MENUS.equals(implName)) {
            return Optional.of(new SearchMenusTool(menuService, permissionService));
        }
        if (IMPL_GET_MENU.equals(implName)) {
            return Optional.of(new GetMenuTool(menuService, permissionService));
        }
        if (IMPL_GET_ROLE_PERMISSIONS.equals(implName)) {
            return Optional.of(new GetRolePermissionsTool(roleService, menuService, permissionService));
        }
        if (IMPL_GET_SYSTEM_INFO.equals(implName)) {
            return Optional.of(new GetSystemInfoTool());
        }
        log.warn("工具实现未在代码侧登记，跳过装配：toolCode={}, implName={}", definition.getToolCode(), implName);
        return Optional.empty();
    }

}
