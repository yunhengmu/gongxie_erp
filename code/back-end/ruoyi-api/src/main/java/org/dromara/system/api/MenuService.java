package org.dromara.system.api;

import org.dromara.system.api.domain.MenuDetailDTO;

import java.util.List;

/**
 * 通用 菜单（权限）服务
 *
 * <p>其中 {@code *ByAi} 系列供 AI 只读权限工具调用：菜单（权限标识）为平台级数据，
 * 不涉及部门数据范围，故不传操作人，仅由工具侧按 {@code system:menu:*} 校验功能权限。</p>
 *
 * @author BizBuddy
 */
public interface MenuService {

    /**
     * 按关键词 / 权限标识 / 状态搜索菜单（权限）（AI 工具调用）。
     *
     * @param keyword 菜单名称关键词，可空（空则不限）
     * @param perms   权限标识关键词，可空（空则不限）
     * @param status  菜单状态（0正常 1停用），可空（空则不限）
     * @return 菜单详情列表（按父菜单、显示顺序排序）
     */
    List<MenuDetailDTO> searchMenusByAi(String keyword, String perms, String status);

    /**
     * 查询菜单（权限）详情（AI 工具调用）。
     *
     * @param menuId 菜单ID
     * @return 菜单详情；菜单不存在时返回 null
     */
    MenuDetailDTO selectMenuDetailByAi(Long menuId);

    /**
     * 查询角色已分配的权限标识集合（AI 工具调用，即「角色权限对应关系」）。
     *
     * @param roleId 角色ID
     * @return 权限标识列表（去重后按字典序）；角色无权限时返回空列表
     */
    List<String> selectRolePermsByAi(Long roleId);

}