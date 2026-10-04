package org.dromara.system.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysUserExportVo;
import org.dromara.system.domain.vo.SysUserVo;

import java.util.List;

/**
 * 用户表 数据层
 *
 * @author Lion Li
 */
public interface SysUserMapper extends BaseMapperPlus<SysUser, SysUserVo>, MPJBaseMapper<SysUser> {

    /**
     * 分页查询用户列表，并进行数据权限控制
     *
     * @param page         分页参数
     * @param queryWrapper 查询条件
     * @return 分页的用户信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default Page<SysUserVo> selectPageUserList(Page<SysUser> page, Wrapper<SysUser> queryWrapper) {
        return this.selectVoPage(page, queryWrapper);
    }

    /**
     * 查询用户列表，并进行数据权限控制
     *
     * @param queryWrapper 查询条件
     * @return 用户信息集合
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default List<SysUserVo> selectUserList(Wrapper<SysUser> queryWrapper) {
        return this.selectVoList(queryWrapper);
    }

    /**
     * 根据条件分页查询用户列表
     *
     * @param user    查询条件
     * @param deptIds 部门ID集合
     * @return 用户信息集合信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "d.dept_id"),
        @DataColumn(key = "userName", value = "u.create_by")
    })
    default List<SysUserExportVo> selectUserExportList(SysUserBo user, List<Long> deptIds) {
        MPJLambdaWrapper<SysUser> wrapper = QueryBuilder.lambdaJoin("u", SysUser.class)
            .selectAll(SysUser.class)
            .selectAs("u1", SysUser::getUserName, SysUserExportVo::getLeaderName)
            .leftJoin(SysDept.class, "d", SysDept::getDeptId, SysUser::getDeptId)
            .leftJoin(SysUser.class, "u1", SysUser::getUserId, SysDept::getLeader)
            .likeIfText("u", SysUser::getUserName, user.getUserName())
            .likeIfText("u", SysUser::getNickName, user.getNickName())
            .eqIfText("u", SysUser::getStatus, user.getStatus())
            .likeIfText("u", SysUser::getPhoneNumber, user.getPhoneNumber())
            .betweenParams("u", SysUser::getCreateTime, user.getParams(), "beginTime", "endTime")
            .inIfNotEmpty("u", SysUser::getDeptId, deptIds)
            .orderByAsc("u", SysUser::getUserId)
            .build();
        return this.selectJoinList(SysUserExportVo.class, wrapper);
    }

    /**
     * 根据条件分页查询已配用户角色列表
     *
     * @param page 分页信息
     * @param user 查询条件
     * @return 用户信息集合信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "d.dept_id"),
        @DataColumn(key = "userName", value = "u.create_by")
    })
    default Page<SysUserVo> selectAllocatedList(Page<SysUserVo> page, SysUserBo user) {
        MPJLambdaWrapper<SysUser> wrapper = this.buildUserRoleJoinWrapper(user)
            .eq(user.getRoleId() != null, "r", SysRole::getRoleId, user.getRoleId())
            .orderByAsc("u", SysUser::getUserId);
        return this.selectJoinPage(page, SysUserVo.class, wrapper);
    }

    /**
     * 根据条件分页查询未分配用户角色列表
     *
     * @param page    分页信息
     * @param user    查询条件
     * @param userIds 未分配用户角色的用户ID列表
     * @return 用户信息集合信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "d.dept_id"),
        @DataColumn(key = "userName", value = "u.create_by")
    })
    default Page<SysUserVo> selectUnallocatedList(Page<SysUserVo> page, SysUserBo user, List<Long> userIds) {
        MPJLambdaWrapper<SysUser> wrapper = this.buildUserRoleJoinWrapper(user)
            .notIn(userIds != null && !userIds.isEmpty(), "u", SysUser::getUserId, userIds)
            .orderByAsc("u", SysUser::getUserId);
        return this.selectJoinPage(page, SysUserVo.class, wrapper);
    }

    /**
     * 根据用户ID统计用户数量
     *
     * @param userId 用户ID
     * @return 用户数量
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default long countUserById(Long userId) {
        return lambda().eq(SysUser::getUserId, userId).count();
    }

    /**
     * 根据条件更新用户数据
     *
     * @param user          要更新的用户实体
     * @param updateWrapper 更新条件封装器
     * @return 更新操作影响的行数
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    int update(@Param(Constants.ENTITY) SysUser user, @Param(Constants.WRAPPER) Wrapper<SysUser> updateWrapper);

    /**
     * 根据用户ID更新用户数据
     *
     * @param user 要更新的用户实体
     * @return 更新操作影响的行数
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    int updateById(@Param(Constants.ENTITY) SysUser user);

    /**
     * AI 用户工具专用：按用户 ID 更新用户（**不带数据权限注解**）。
     *
     * <p>{@link #updateById} 带 {@code @DataPermission}，其处理器需要 Sa-Token 登录上下文；
     * AI 工具运行在 Reactor 线程（无登录上下文），调用它会抛
     * {@code SaTokenContextException: SaTokenContext 上下文尚未初始化}，故为 AI 链路单列此通道
     * （与部门工具「AI 侧不叠加角色数据权限、越权面由菜单权限收敛」同口径）。</p>
     *
     * <p>调用方须传齐**全部可变字段**（未变更的字段传原值）：本语句为整行覆盖式更新，
     * 不改动 password / avatar / user_type / login_ip / login_date 与审计创建列。
     * 因未经 MyBatis-Plus 的自动填充，{@code update_by} 记录的是调用方显式传入的操作人。</p>
     *
     * @param user 用户实体（须含 userId 与全部可变字段）
     * @return 更新影响行数
     */
    @Update("""
        update sys_user
           set dept_id = #{et.deptId},
               user_name = #{et.userName},
               nick_name = #{et.nickName},
               email = #{et.email},
               phone_number = #{et.phoneNumber},
               gender = #{et.gender},
               status = #{et.status},
               remark = #{et.remark},
               update_by = #{et.updateBy},
               update_time = now()
         where user_id = #{et.userId} and del_flag = '0'
        """)
    int updateByAi(@Param(Constants.ENTITY) SysUser user);

    /**
     * 构建用户与角色关联查询条件
     *
     * @param user 查询条件
     * @return 用户角色关联查询包装器
     */
    default MPJLambdaWrapper<SysUser> buildUserRoleJoinWrapper(SysUserBo user) {
        return QueryBuilder.lambdaJoin("u", SysUser.class)
            .distinct()
            .selectAll(SysUser.class)
            .leftJoin(SysDept.class, "d", SysDept::getDeptId, SysUser::getDeptId)
            .leftJoin(SysUserRole.class, "sur", SysUserRole::getUserId, SysUser::getUserId)
            .leftJoin(SysRole.class, "r", SysRole::getRoleId, SysUserRole::getRoleId)
            .likeIfText("u", SysUser::getUserName, user.getUserName())
            .eqIfText("u", SysUser::getStatus, user.getStatus())
            .likeIfText("u", SysUser::getPhoneNumber, user.getPhoneNumber())
            .build();
    }

}
