package org.dromara.system.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.enums.DataScopeType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysDataScopeService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 工具的行级数据权限解析器。
 *
 * <p>AI 工具执行在 Reactor 线程，Sa-Token 与请求上下文均不可用，框架的
 * {@code PlusDataPermissionHandler} 在 {@code LoginHelper.isSuperAdmin()} 一处即抛
 * {@code SaTokenContextException}，因此带 {@code @DataPermission} 的 Mapper 方法不能在 AI 路径使用。
 * 本解析器按**显式传入的操作人**复刻框架 {@link DataScopeType} 的六档语义，供 AI 侧的
 * 部门 / 用户工具在查询与写入前做可见范围判定。</p>
 *
 * <p><b>口径与框架对齐</b>：超管不限；任一角色为「全部数据权限」不限；用户无任何角色时与框架一致
 * **不加范围条件**（框架在未约束场景下 `scopeRoles` 为空即返回空条件）；多角色之间为 **OR** 合并。
 * 「自定义」档取 {@code sys_role_dept}，「本部门及以下」档取 {@code sys_dept} 子树，
 * 与框架模板里的 {@code @sdss.getRoleCustom} / {@code @sdss.getDeptAndChild} 同源。</p>
 *
 * <p><b>唯一的刻意偏离</b>：读单点（如「查看我自己的部门」）额外豁免**操作人本人所属部门**，
 * 与页面「个人中心」一致；框架在「仅本人」档下的部门查询会退化为 `1 = 0`（不可见任何部门），
 * 若照搬会导致 {@code get_current_dept} 这类无越权面的工具失效。</p>
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiDataScopeResolver {

    private final SysUserMapper userMapper;

    private final SysRoleMapper roleMapper;

    private final SysDeptMapper deptMapper;

    /**
     * 数据权限模板服务（`sdss`），与框架拼 SQL 时使用同一份「自定义部门」缓存
     */
    private final ISysDataScopeService dataScopeService;

    /**
     * 解析指定操作人参与的数据范围。
     *
     * @param operatorId 操作人用户 ID（AI 工具从运行时上下文取得）
     * @return 数据范围判定对象；无法识别操作人时返回 fail-closed（不可见任何数据）
     */
    public AiDataScope resolve(Long operatorId) {
        SysUser operator = ObjectUtil.isNull(operatorId) ? null : userMapper.selectById(operatorId);
        if (ObjectUtil.isNull(operator)) {
            // 无法识别操作人（如 MCP 出口无平台用户）：fail-closed，不给任何可见范围
            return new AiDataScope(false, Set.of(), false, operatorId, null);
        }
        Long ownDeptId = operator.getDeptId();
        if (LoginHelper.isSuperAdmin(operatorId)) {
            return unlimited(operatorId, ownDeptId);
        }
        List<SysRoleVo> roles = roleMapper.selectRolesByUserId(operatorId);
        if (CollUtil.isEmpty(roles)) {
            // 与框架一致：未参与任何角色时该查询不叠加数据范围条件
            return unlimited(operatorId, ownDeptId);
        }
        Set<Long> deptIds = new LinkedHashSet<>();
        boolean selfIncluded = false;
        for (SysRoleVo role : roles) {
            DataScopeType type = DataScopeType.findCode(role.getDataScope());
            if (ObjectUtil.isNull(type)) {
                throw new ServiceException("角色数据范围异常 => " + role.getDataScope());
            }
            switch (type) {
                case ALL -> {
                    return unlimited(operatorId, ownDeptId);
                }
                case CUSTOM -> deptIds.addAll(parseDeptIds(dataScopeService.getRoleCustom(role.getRoleId())));
                case DEPT -> {
                    if (ObjectUtil.isNotNull(ownDeptId)) {
                        deptIds.add(ownDeptId);
                    }
                }
                case DEPT_AND_CHILD -> addOwnDeptAndChild(deptIds, ownDeptId);
                case DEPT_AND_CHILD_OR_SELF -> {
                    addOwnDeptAndChild(deptIds, ownDeptId);
                    selfIncluded = true;
                }
                case SELF -> selfIncluded = true;
            }
        }
        return new AiDataScope(false, Collections.unmodifiableSet(deptIds), selfIncluded, operatorId, ownDeptId);
    }

    /**
     * 构造「不限」的数据范围。
     *
     * @param operatorId 操作人用户 ID
     * @param ownDeptId  操作人所属部门
     * @return 数据范围判定对象
     */
    private static AiDataScope unlimited(Long operatorId, Long ownDeptId) {
        return new AiDataScope(true, Set.of(), false, operatorId, ownDeptId);
    }

    /**
     * 把操作人所属部门及其全部下级部门并入可见集合。
     *
     * @param deptIds   可见部门集合（累加）
     * @param ownDeptId 操作人所属部门
     */
    private void addOwnDeptAndChild(Set<Long> deptIds, Long ownDeptId) {
        if (ObjectUtil.isNotNull(ownDeptId)) {
            deptIds.addAll(deptMapper.selectDeptAndChildById(ownDeptId));
        }
    }

    /**
     * 解析逗号分隔的部门 ID 串（框架在「无自定义部门」时以 {@code -1} 作占位，等价于空集）。
     *
     * @param raw 逗号分隔的部门 ID 串
     * @return 去重后的部门 ID 集合
     */
    private static Set<Long> parseDeptIds(String raw) {
        Set<Long> ids = new LinkedHashSet<>();
        if (StringUtils.isBlank(raw)) {
            return ids;
        }
        for (String item : StringUtils.splitList(raw)) {
            Long id = Convert.toLong(item, null);
            if (ObjectUtil.isNotNull(id) && id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * AI 工具路径的数据范围判定结果（不可变）。
     *
     * @param unlimited    是否不限（超管 / 全部数据权限 / 无角色）
     * @param deptIds      部门维度的可见部门集合（不含「仅本人」豁免；可能为空集）
     * @param selfIncluded 是否包含「仅本人」（数据范围 5 / 6）
     * @param operatorId   操作人用户 ID
     * @param ownDeptId    操作人所属部门（读单点时豁免可见，与「个人中心」一致）
     */
    public record AiDataScope(boolean unlimited, Set<Long> deptIds, boolean selfIncluded,
                              Long operatorId, Long ownDeptId) {

        /**
         * 列表查询用的部门过滤集合。
         *
         * @return {@code null} 表示不限（不加条件）；空集表示不可见任何部门
         */
        public Set<Long> filterDeptIds() {
            return unlimited ? null : deptIds;
        }

        /**
         * 单个部门是否可读（读单点豁免操作人本人所属部门）。
         *
         * @param deptId 部门 ID
         * @return 是否可读
         */
        public boolean canViewDept(Long deptId) {
            if (ObjectUtil.isNull(deptId)) {
                return false;
            }
            if (unlimited || deptIds.contains(deptId)) {
                return true;
            }
            return ObjectUtil.isNotNull(ownDeptId) && ownDeptId.equals(deptId);
        }

        /**
         * 单个部门是否可写（新增子部门 / 修改 / 删除；不豁免本人所属部门）。
         *
         * @param deptId 部门 ID
         * @return 是否可写
         */
        public boolean canWriteDept(Long deptId) {
            if (ObjectUtil.isNull(deptId)) {
                return false;
            }
            return unlimited || deptIds.contains(deptId);
        }

        /**
         * 单个用户是否可见 / 可写（本人例外仅由「仅本人」档生效）。
         *
         * @param userId 目标用户 ID
         * @param deptId 目标用户所属部门 ID
         * @return 是否可见
         */
        public boolean canViewUser(Long userId, Long deptId) {
            if (unlimited) {
                return true;
            }
            if (ObjectUtil.isNotNull(deptId) && deptIds.contains(deptId)) {
                return true;
            }
            return selfIncluded && ObjectUtil.isNotNull(userId) && userId.equals(operatorId);
        }

    }

}