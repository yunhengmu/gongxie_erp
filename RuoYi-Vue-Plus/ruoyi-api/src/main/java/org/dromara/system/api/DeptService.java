package org.dromara.system.api;

import org.dromara.system.api.domain.DeptDTO;
import org.dromara.system.api.domain.DeptDetailDTO;
import org.dromara.system.api.domain.DeptSaveDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 通用 部门服务
 *
 * <p>其中 {@code *ByAi} 系列供 AI 工具调用：由调用方显式传入操作人，避免依赖线程上下文
 * （AI 工具执行在 Reactor 线程，Sa-Token 与数据权限上下文均不可用），
 * 并在服务内复刻 {@code SysDeptController} 的业务校验（父部门可用性 / 名称唯一 / 停用前置检查 /
 * 删除保护），从而与页面操作保持同一口径。</p>
 *
 * <p><b>数据权限</b>：AI 路径无法使用带 {@code @DataPermission} 的 Mapper 方法，
 * 改由服务实现按显式操作人解析角色数据范围（六档，见 {@code AiDataScopeResolver}）后叠加过滤与校验，
 * 与页面口径一致；超管与「全部数据权限」不限。</p>
 *
 * @author Lion Li
 */
public interface DeptService {

    /**
     * 通过部门ID查询部门名称
     *
     * @param deptIds 部门ID串逗号分隔
     * @return 部门名称串逗号分隔
     */
    String selectDeptNameByIds(String deptIds);

    /**
     * 根据部门ID查询部门负责人
     *
     * @param deptId 部门ID，用于指定需要查询的部门
     * @return 返回该部门的负责人ID
     */
    Long selectDeptLeaderById(Long deptId);

    /**
     * 查询部门
     *
     * @return 部门列表
     */
    List<DeptDTO> selectDeptsByList();

    /**
     * 根据部门 ID 列表查询部门名称映射关系
     *
     * @param deptIds 部门 ID 列表
     * @return Map，其中 key 为部门 ID，value 为对应的部门名称
     */
    Map<Long, String> selectDeptNamesByIds(Collection<Long> deptIds);

    /**
     * 按关键词 / 状态搜索部门（AI 工具调用；结果按操作人的角色数据范围过滤）。
     *
     * @param keyword    部门名称关键词，可空（空则不限）
     * @param status     部门状态（0正常 1停用），可空（空则不限）
     * @param operatorId 操作人用户 ID（用于解析角色数据范围）
     * @return 部门详情列表（按上级、显示顺序排序）
     */
    List<DeptDetailDTO> searchDeptsByAi(String keyword, String status, Long operatorId);

    /**
     * 查询部门详情（AI 工具调用；超出操作人数据范围时拒绝）。
     *
     * @param deptId     部门ID
     * @param operatorId 操作人用户 ID（用于解析角色数据范围）
     * @return 部门详情；部门不存在时返回 null
     */
    DeptDetailDTO selectDeptDetailByAi(Long deptId, Long operatorId);

    /**
     * 新增部门（AI 工具调用；上级部门须在操作人数据范围内）。
     *
     * @param dto 部门信息与操作人
     * @return 新增的部门ID
     */
    Long createDeptByAi(DeptSaveDTO dto);

    /**
     * 修改部门（AI 工具调用；未提供的字段沿用原值，目标与新的上级部门均须在操作人数据范围内）。
     *
     * @param dto 部门信息与操作人
     */
    void updateDeptByAi(DeptSaveDTO dto);

    /**
     * 删除部门（AI 工具调用；含默认部门 / 下级部门 / 用户 / 岗位保护，目标须在操作人数据范围内）。
     *
     * @param deptId     部门ID
     * @param operatorId 操作人用户 ID（用于解析角色数据范围）
     */
    void deleteDeptByAi(Long deptId, Long operatorId);

}
