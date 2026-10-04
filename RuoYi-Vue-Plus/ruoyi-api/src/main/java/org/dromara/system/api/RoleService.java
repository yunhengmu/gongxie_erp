package org.dromara.system.api;

import org.dromara.system.api.domain.RoleDetailDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 通用 角色服务
 *
 * <p>其中 {@code *ByAi} 系列供 AI 只读角色工具调用：角色为平台级数据、不涉及部门数据范围，
 * 故不传操作人，仅由工具侧按 {@code system:role:*} 校验功能权限。</p>
 *
 * @author AprilWind
 */
public interface RoleService {

    /**
     * 根据角色 ID 列表查询角色名称映射关系
     *
     * @param roleIds 角色 ID 列表
     * @return Map，其中 key 为角色 ID，value 为对应的角色名称
     */
    Map<Long, String> selectRoleNamesByIds(Collection<Long> roleIds);

    /**
     * 按关键词 / 状态搜索角色（AI 工具调用）。
     *
     * @param keyword 角色名称或权限字符关键词，可空（空则不限）
     * @param status  角色状态（0正常 1停用），可空（空则不限）
     * @return 角色详情列表（按显示顺序排序）
     */
    List<RoleDetailDTO> searchRolesByAi(String keyword, String status);

    /**
     * 查询角色详情（AI 工具调用）。
     *
     * @param roleId 角色ID
     * @return 角色详情；角色不存在时返回 null
     */
    RoleDetailDTO selectRoleDetailByAi(Long roleId);

}
