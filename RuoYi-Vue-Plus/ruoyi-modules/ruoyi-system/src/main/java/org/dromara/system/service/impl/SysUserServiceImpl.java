package org.dromara.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.*;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.dromara.system.api.domain.UserDetailDTO;
import org.dromara.system.api.domain.UserSaveDTO;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysPost;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserPost;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysPostVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.domain.vo.SysUserExportVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.*;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysUserService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 用户 业务层处理
 *
 * @author Lion Li
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SysUserServiceImpl implements ISysUserService, UserService {

    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final SysPostMapper postMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserPostMapper userPostMapper;
    private final ISysConfigService configService;

    /**
     * AI 路径的角色数据范围解析器（AI 工具跑在 Reactor 线程，不能用 {@code @DataPermission}）
     */
    private final AiDataScopeResolver dataScopeResolver;

    /**
     * AI 用户工具：搜索单次返回条数上限（避免全量用户撑爆模型上下文）
     */
    private static final int AI_SEARCH_LIMIT = 200;

    /**
     * AI 用户工具：新增用户的初始密码配置键（与「用户导入」同一口径）
     */
    private static final String CONFIG_INIT_PASSWORD = "sys.user.initPassword";

    /**
     * AI 用户工具：初始密码配置缺失时的兜底值
     */
    private static final String CONFIG_INIT_PASSWORD_DEFAULT = "123456";

    /**
     * 分页查询用户列表。
     *
     * @param user      用户筛选条件
     * @param pageQuery 分页参数
     * @return 用户分页结果
     */
    @Override
    public PageResult<SysUserVo> selectPageUserList(SysUserBo user, PageQuery pageQuery) {
        Page<SysUserVo> page = userMapper.selectPageUserList(pageQuery.build(), this.buildQueryWrapper(user));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 根据条件分页查询用户列表
     *
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    @Override
    public List<SysUserExportVo> selectUserExportList(SysUserBo user) {
        List<Long> deptIds = ObjectUtil.isNotNull(user.getDeptId()) ? deptMapper.selectDeptAndChildById(user.getDeptId()) : null;
        return userMapper.selectUserExportList(user, deptIds);
    }

    /**
     * 构造用户列表查询条件。
     *
     * @param user 用户筛选条件
     * @return 叠加部门、状态、时间区间等条件的查询包装器
     */
    private Wrapper<SysUser> buildQueryWrapper(SysUserBo user) {
        Map<String, Object> params = user.getParams();
        LambdaQueryWrapper<SysUser> wrapper = QueryBuilder.lambda(SysUser.class)
            .eqIfPresent(SysUser::getUserId, user.getUserId())
            .in(StringUtils.isNotBlank(user.getUserIds()), SysUser::getUserId, StringUtils.splitTo(user.getUserIds(), Convert::toLong))
            .likeIfText(SysUser::getUserName, user.getUserName())
            .likeIfText(SysUser::getNickName, user.getNickName())
            .eqIfText(SysUser::getStatus, user.getStatus())
            .likeIfText(SysUser::getPhoneNumber, user.getPhoneNumber())
            .betweenParams(SysUser::getCreateTime, params, "beginTime", "endTime")
            .and(ObjectUtil.isNotNull(user.getDeptId()), w -> {
                List<Long> ids = deptMapper.selectDeptAndChildById(user.getDeptId());
                w.in(SysUser::getDeptId, ids);
            })
            .orderByAsc(SysUser::getUserId)
            .build();
        if (StringUtils.isNotBlank(user.getExcludeUserIds())) {
            wrapper.notIn(SysUser::getUserId, StringUtils.splitTo(user.getExcludeUserIds(), Convert::toLong));
        }
        return wrapper;
    }

    /**
     * 根据条件分页查询已分配用户角色列表
     *
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    @Override
    public PageResult<SysUserVo> selectAllocatedList(SysUserBo user, PageQuery pageQuery) {
        Page<SysUserVo> page = userMapper.selectAllocatedList(pageQuery.build(), user);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 根据条件分页查询未分配用户角色列表
     *
     * @param user 用户信息
     * @return 用户信息集合信息
     */
    @Override
    public PageResult<SysUserVo> selectUnallocatedList(SysUserBo user, PageQuery pageQuery) {
        List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(user.getRoleId());
        Page<SysUserVo> page = userMapper.selectUnallocatedList(pageQuery.build(), user, userIds);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 通过用户名查询用户
     *
     * @param userName 用户名
     * @return 用户对象信息
     */
    @Override
    public SysUserVo selectUserByUserName(String userName) {
        return userMapper.lambda().eq(SysUser::getUserName, userName).voOne();
    }

    /**
     * 通过手机号查询用户
     *
     * @param phoneNumber 手机号
     * @return 用户对象信息
     */
    @Override
    public SysUserVo selectUserByPhoneNumber(String phoneNumber) {
        return userMapper.lambda().eq(SysUser::getPhoneNumber, phoneNumber).voOne();
    }

    /**
     * 通过用户ID查询用户
     *
     * @param userId 用户ID
     * @return 用户对象信息
     */
    @Override
    public SysUserVo selectUserById(Long userId) {
        SysUserVo user = userMapper.selectVoById(userId);
        if (ObjectUtil.isNull(user)) {
            return user;
        }
        user.setRoles(roleMapper.selectRolesByUserId(user.getUserId()));
        return user;
    }

    /**
     * 通过用户ID串查询用户
     *
     * @param userIds 用户ID串
     * @param deptId  部门id
     * @return 用户列表信息
     */
    @Override
    public List<SysUserVo> selectUserByIds(Collection<Long> userIds, Long deptId) {
        return userMapper.selectUserList(userMapper.lambda()
            .select(SysUser::getUserId, SysUser::getUserName, SysUser::getNickName)
            .eq(SysUser::getStatus, SystemConstants.NORMAL)
            .eqIfPresent(SysUser::getDeptId, deptId)
            .inIfNotEmpty(SysUser::getUserId, userIds)
            .build());
    }

    /**
     * 查询用户所属角色组
     *
     * @param userId 用户ID
     * @return 结果
     */
    @Override
    public String selectUserRoleGroup(Long userId) {
        List<SysRoleVo> list = roleMapper.selectRolesByUserId(userId);
        if (CollUtil.isEmpty(list)) {
            return StringUtils.EMPTY;
        }
        return StreamUtils.join(list, SysRoleVo::getRoleName);
    }

    /**
     * 查询用户所属岗位组
     *
     * @param userId 用户ID
     * @return 结果
     */
    @Override
    public String selectUserPostGroup(Long userId) {
        List<SysPostVo> list = postMapper.selectPostsByUserId(userId);
        if (CollUtil.isEmpty(list)) {
            return StringUtils.EMPTY;
        }
        return StreamUtils.join(list, SysPostVo::getPostName);
    }

    /**
     * 校验用户账号是否唯一
     *
     * @param user 用户信息
     * @return 结果
     */
    @Override
    public boolean checkUserNameUnique(SysUserBo user) {
        boolean exist = userMapper.lambda()
            .eq(SysUser::getUserName, user.getUserName())
            .neIfPresent(SysUser::getUserId, user.getUserId())
            .exists();
        return !exist;
    }

    /**
     * 校验手机号码是否唯一
     *
     * @param user 用户信息
     */
    @Override
    public boolean checkPhoneUnique(SysUserBo user) {
        boolean exist = userMapper.lambda()
            .eq(SysUser::getPhoneNumber, user.getPhoneNumber())
            .neIfPresent(SysUser::getUserId, user.getUserId())
            .exists();
        return !exist;
    }

    /**
     * 校验email是否唯一
     *
     * @param user 用户信息
     */
    @Override
    public boolean checkEmailUnique(SysUserBo user) {
        boolean exist = userMapper.lambda()
            .eq(SysUser::getEmail, user.getEmail())
            .neIfPresent(SysUser::getUserId, user.getUserId())
            .exists();
        return !exist;
    }

    /**
     * 校验用户是否允许操作
     *
     * @param userId 用户ID
     */
    @Override
    public void checkUserAllowed(Long userId) {
        if (ObjectUtil.isNotNull(userId) && LoginHelper.isSuperAdmin(userId)) {
            throw new ServiceException("不允许操作超级管理员用户");
        }
    }

    /**
     * 校验用户是否有数据权限
     *
     * @param userId 用户id
     */
    @Override
    public void checkUserDataScope(Long userId) {
        if (ObjectUtil.isNull(userId)) {
            return;
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (userMapper.countUserById(userId) == 0) {
            throw new ServiceException("没有权限访问用户数据！");
        }
    }

    /**
     * 新增保存用户信息
     *
     * @param user 用户信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertUser(SysUserBo user) {
        SysUser sysUser = MapstructUtils.convert(user, SysUser.class);
        // 新增用户信息
        int rows = userMapper.insert(sysUser);
        user.setUserId(sysUser.getUserId());
        // 新增用户岗位关联
        insertUserPost(user, false);
        // 新增用户与角色管理
        insertUserRole(user, false);
        return rows;
    }

    /**
     * 注册用户信息
     *
     * @param user 用户信息
     * @return 结果
     */
    @Override
    public boolean registerUser(SysUserBo user) {
        user.setCreateBy(0L);
        user.setUpdateBy(0L);
        SysUser sysUser = MapstructUtils.convert(user, SysUser.class);
        return userMapper.insert(sysUser) > 0;
    }

    /**
     * 修改保存用户信息
     *
     * @param user 用户信息
     * @return 结果
     */
    @Override
    @CacheEvict(cacheNames = CacheNames.SYS_NICKNAME, key = "#user.userId")
    @Transactional(rollbackFor = Exception.class)
    public int updateUser(SysUserBo user) {
        // 新增用户与角色管理
        insertUserRole(user, true);
        // 新增用户与岗位管理
        insertUserPost(user, true);
        SysUser sysUser = MapstructUtils.convert(user, SysUser.class);
        // 防止错误更新后导致的数据误删除
        int flag = userMapper.updateById(sysUser);
        if (flag < 1) {
            throw new ServiceException("修改用户{}信息失败", user.getUserName());
        }
        return flag;
    }

    /**
     * 用户授权角色
     *
     * @param userId  用户ID
     * @param roleIds 角色组
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void insertUserAuth(Long userId, Long[] roleIds) {
        insertUserRole(userId, roleIds, true);
    }

    /**
     * 修改用户状态
     *
     * @param userId 用户ID
     * @param status 账号状态
     * @return 结果
     */
    @Override
    public int updateUserStatus(Long userId, String status) {
        return userMapper.lambda()
            .set(SysUser::getStatus, status)
            .eq(SysUser::getUserId, userId)
            .updateCount();
    }

    /**
     * 修改用户基本信息
     *
     * @param user 用户信息
     * @return 结果
     */
    @CacheEvict(cacheNames = CacheNames.SYS_NICKNAME, key = "#user.userId")
    @Override
    public int updateUserProfile(SysUserBo user) {
        return userMapper.lambda()
            .setIfPresent(SysUser::getNickName, user.getNickName())
            .setIfPresent(SysUser::getAvatar, user.getAvatar())
            .setIfPresent(SysUser::getPhoneNumber, user.getPhoneNumber())
            .setIfPresent(SysUser::getEmail, user.getEmail())
            .setIfPresent(SysUser::getGender, user.getGender())
            .eq(SysUser::getUserId, user.getUserId())
            .updateCount();
    }

    /**
     * 重置用户密码
     *
     * @param userId   用户ID
     * @param password 密码
     * @return 结果
     */
    @Override
    public int resetUserPwd(Long userId, String password) {
        return userMapper.lambda()
            .set(SysUser::getPassword, password)
            .eq(SysUser::getUserId, userId)
            .updateCount();
    }

    /**
     * 新增用户角色信息
     *
     * @param user  用户对象
     * @param clear 清除已存在的关联数据
     */
    private void insertUserRole(SysUserBo user, boolean clear) {
        this.insertUserRole(user.getUserId(), user.getRoleIds(), clear);
    }

    /**
     * 新增用户岗位信息
     *
     * @param user  用户对象
     * @param clear 清除已存在的关联数据
     */
    private void insertUserPost(SysUserBo user, boolean clear) {
        Long[] postIdArr = user.getPostIds();
        if (ArrayUtil.isEmpty(postIdArr)) {
            return;
        }
        List<Long> postIds = Arrays.asList(postIdArr);

        // 校验是否有权限操作这些岗位（含数据权限控制）
        if (postMapper.selectPostCount(postIds) != postIds.size()) {
            throw new ServiceException("没有权限访问岗位的数据");
        }

        // 是否清除旧的用户岗位绑定
        if (clear) {
            userPostMapper.lambda().eq(SysUserPost::getUserId, user.getUserId()).delete();
        }

        // 构建用户岗位关联列表并批量插入
        List<SysUserPost> list = StreamUtils.toList(postIds,
            postId -> {
                SysUserPost up = new SysUserPost();
                up.setUserId(user.getUserId());
                up.setPostId(postId);
                return up;
            });
        userPostMapper.insertBatch(list);
    }

    /**
     * 新增用户角色信息
     *
     * @param userId  用户ID
     * @param roleIds 角色组
     * @param clear   清除已存在的关联数据
     */
    private void insertUserRole(Long userId, Long[] roleIds, boolean clear) {
        if (ArrayUtil.isEmpty(roleIds)) {
            return;
        }

        List<Long> roleList = new ArrayList<>(Arrays.asList(roleIds));

        // 非超级管理员，禁止包含超级管理员角色
        if (!LoginHelper.isSuperAdmin(userId)) {
            roleList.remove(SystemConstants.SUPER_ADMIN_ROLE_ID);
        }

        // 移除超管角色后若无剩余角色，说明仅选了超管角色且不允许分配，显式报错
        if (roleList.isEmpty()) {
            throw new ServiceException("不允许为普通用户分配超级管理员角色，请至少选择一个其他角色");
        }

        // 校验是否有权限访问这些角色（含数据权限控制）
        if (roleMapper.selectRoleCount(roleList) != roleList.size()) {
            throw new ServiceException("没有权限访问角色的数据");
        }

        // 是否清除原有绑定
        if (clear) {
            userRoleMapper.lambda().eq(SysUserRole::getUserId, userId).delete();
        }

        // 批量插入用户-角色关联
        List<SysUserRole> list = StreamUtils.toList(roleList,
            roleId -> {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                return ur;
            });
        userRoleMapper.insertBatch(list);
    }

    /**
     * 通过用户ID删除用户
     *
     * @param userId 用户ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUserById(Long userId) {
        // 删除用户与角色关联
        userRoleMapper.lambda().eq(SysUserRole::getUserId, userId).delete();
        // 删除用户与岗位表
        userPostMapper.lambda().eq(SysUserPost::getUserId, userId).delete();
        // 防止更新失败导致的数据删除
        int flag = userMapper.deleteById(userId);
        if (flag < 1) {
            throw new ServiceException("删除用户失败!");
        }
        return flag;
    }

    /**
     * 批量删除用户信息
     *
     * @param userIds 需要删除的用户ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteUserByIds(Long[] userIds) {
        for (Long userId : userIds) {
            checkUserAllowed(userId);
            checkUserDataScope(userId);
        }
        List<Long> ids = List.of(userIds);
        // 删除用户与角色关联
        userRoleMapper.lambda().in(SysUserRole::getUserId, ids).delete();
        // 删除用户与岗位表
        userPostMapper.lambda().in(SysUserPost::getUserId, ids).delete();
        // 防止更新失败导致的数据删除
        int flag = userMapper.deleteByIds(ids);
        if (flag < 1) {
            throw new ServiceException("删除用户失败!");
        }
        return flag;
    }

    /**
     * 通过部门id查询当前部门所有用户
     *
     * @param deptId 部门ID
     * @return 用户信息集合信息
     */
    @Override
    public List<SysUserVo> selectUserListByDept(Long deptId) {
        return userMapper.lambda()
            .eq(SysUser::getDeptId, deptId)
            .orderByAsc(SysUser::getUserId)
            .voList();
    }

    /**
     * 通过用户ID查询用户账户
     *
     * @param userId 用户ID
     * @return 用户账户
     */
    @Cacheable(cacheNames = CacheNames.SYS_USER_NAME, key = "#userId")
    @Override
    public String selectUserNameById(Long userId) {
        SysUser sysUser = userMapper.lambda()
            .select(SysUser::getUserName)
            .eq(SysUser::getUserId, userId)
            .one();
        return ObjectUtils.notNullGetter(sysUser, SysUser::getUserName);
    }

    /**
     * 通过用户ID查询用户昵称
     *
     * @param userId 用户ID
     * @return 用户昵称
     */
    @Override
    @Cacheable(cacheNames = CacheNames.SYS_NICKNAME, key = "#userId")
    public String selectNicknameById(Long userId) {
        SysUser sysUser = userMapper.lambda()
            .select(SysUser::getNickName)
            .eq(SysUser::getUserId, userId)
            .one();
        return ObjectUtils.notNullGetter(sysUser, SysUser::getNickName);
    }

    /**
     * 通过用户ID查询用户昵称
     *
     * @param userIds 用户ID 多个用逗号隔开
     * @return 用户昵称
     */
    @Override
    public String selectNicknameByIds(String userIds) {
        List<String> list = new ArrayList<>();
        for (Long id : StringUtils.splitTo(userIds, Convert::toLong)) {
            String nickname = SpringUtils.getAopProxy(this).selectNicknameById(id);
            if (StringUtils.isNotBlank(nickname)) {
                list.add(nickname);
            }
        }
        return StringUtils.joinComma(list);
    }

    /**
     * 通过用户ID查询用户手机号
     *
     * @param userId 用户id
     * @return 用户手机号
     */
    @Override
    public String selectPhonenumberById(Long userId) {
        SysUser sysUser = userMapper.lambda()
            .select(SysUser::getPhoneNumber)
            .eq(SysUser::getUserId, userId)
            .one();
        return ObjectUtils.notNullGetter(sysUser, SysUser::getPhoneNumber);
    }

    /**
     * 通过用户ID查询用户邮箱
     *
     * @param userId 用户id
     * @return 用户邮箱
     */
    @Override
    public String selectEmailById(Long userId) {
        SysUser sysUser = userMapper.lambda()
            .select(SysUser::getEmail)
            .eq(SysUser::getUserId, userId)
            .one();
        return ObjectUtils.notNullGetter(sysUser, SysUser::getEmail);
    }

    /**
     * 通过用户ID查询用户
     *
     * @param userId 用户id
     * @return 用户列表
     */
    @Override
    public UserDTO selectById(Long userId) {
        SysUserVo vo = userMapper.lambda()
            .select(SysUser::getUserId, SysUser::getDeptId, SysUser::getUserName,
                SysUser::getNickName, SysUser::getUserType, SysUser::getEmail,
                SysUser::getPhoneNumber, SysUser::getGender, SysUser::getStatus,
                SysUser::getCreateTime)
            .eq(SysUser::getStatus, SystemConstants.NORMAL)
            .eq(SysUser::getUserId, userId)
            .voOne();
        return BeanUtil.copyProperties(vo, UserDTO.class);
    }

    /**
     * 通过用户ID查询用户列表
     *
     * @param userIds 用户ids
     * @return 用户列表
     */
    @Override
    public List<UserDTO> selectListByIds(Collection<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return List.of();
        }
        List<SysUserVo> list = userMapper.lambda()
            .select(SysUser::getUserId, SysUser::getDeptId, SysUser::getUserName,
                SysUser::getNickName, SysUser::getUserType, SysUser::getEmail,
                SysUser::getPhoneNumber, SysUser::getGender, SysUser::getStatus,
                SysUser::getCreateTime)
            .eq(SysUser::getStatus, SystemConstants.NORMAL)
            .in(SysUser::getUserId, userIds)
            .voList();
        return BeanUtil.copyToList(list, UserDTO.class);
    }

    /**
     * 通过角色ID查询用户ID
     *
     * @param roleIds 角色ids
     * @return 用户ids
     */
    @Override
    public List<Long> selectUserIdsByRoleIds(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return List.of();
        }
        List<SysUserRole> userRoles = userRoleMapper.lambda().in(SysUserRole::getRoleId, roleIds).list();
        return StreamUtils.toList(userRoles, SysUserRole::getUserId);
    }

    /**
     * 通过角色ID查询用户
     *
     * @param roleIds 角色ids
     * @return 用户
     */
    @Override
    public List<UserDTO> selectUsersByRoleIds(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return List.of();
        }

        // 通过角色ID获取用户角色信息
        List<SysUserRole> userRoles = userRoleMapper.lambda().in(SysUserRole::getRoleId, roleIds).list();

        // 获取用户ID列表
        Set<Long> userIds = StreamUtils.toSet(userRoles, SysUserRole::getUserId);

        return this.selectListByIds(new ArrayList<>(userIds));
    }

    /**
     * 通过部门ID查询用户
     *
     * @param deptIds 部门ids
     * @return 用户
     */
    @Override
    public List<UserDTO> selectUsersByDeptIds(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return List.of();
        }
        List<SysUserVo> list = userMapper.lambda()
            .select(SysUser::getUserId, SysUser::getUserName, SysUser::getNickName, SysUser::getEmail, SysUser::getPhoneNumber)
            .eq(SysUser::getStatus, SystemConstants.NORMAL)
            .in(SysUser::getDeptId, deptIds)
            .voList();
        return BeanUtil.copyToList(list, UserDTO.class);
    }

    /**
     * 通过岗位ID查询用户
     *
     * @param postIds 岗位ids
     * @return 用户
     */
    @Override
    public List<UserDTO> selectUsersByPostIds(Collection<Long> postIds) {
        if (CollUtil.isEmpty(postIds)) {
            return List.of();
        }

        // 通过岗位ID获取用户岗位信息
        List<SysUserPost> userPosts = userPostMapper.lambda().in(SysUserPost::getPostId, postIds).list();

        // 获取用户ID列表
        Set<Long> userIds = StreamUtils.toSet(userPosts, SysUserPost::getUserId);

        return this.selectListByIds(new ArrayList<>(userIds));
    }

    /**
     * 根据用户 ID 列表查询用户昵称映射关系
     *
     * @param userIds 用户 ID 列表
     * @return Map，其中 key 为用户 ID，value 为对应的用户昵称
     */
    @Override
    public Map<Long, String> selectUserNicksByIds(Collection<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        List<SysUser> list = userMapper.lambda()
            .select(SysUser::getUserId, SysUser::getNickName)
            .in(SysUser::getUserId, userIds)
            .list();
        return StreamUtils.toMap(list, SysUser::getUserId, SysUser::getNickName);
    }

    /**
     * 按关键词 / 部门 / 状态搜索用户（AI 工具调用；结果按操作人角色数据范围过滤）。
     *
     * <p>筛选口径与 {@code SysUserController#list} 一致（关键词匹配账号 / 昵称 / 手机号，部门含下级），
     * 但**不叠加框架的 {@code @DataPermission}**：RuoYi 的数据权限处理器在非登录线程（AI 工具跑在
     * Reactor 线程，{@code LoginHelper} 线程上下文为空）取不到登录用户会直接失败，
     * 故查询使用无 {@code @DataPermission} 的链式构造器，**数据范围由 {@link #dataScopeResolver}
     * 按显式操作人解析后叠加**，功能权限由工具侧按操作人菜单权限校验。</p>
     *
     * @param keyword    关键词，可空（空则不限）
     * @param deptId     部门 ID，可空（空则不限；非空含其所有下级部门，且须在数据范围内）
     * @param status     账号状态（0正常 1停用），可空（空则不限）
     * @param operatorId 操作人用户 ID
     * @return 用户详情列表（按用户 ID 升序，最多 {@code AI_SEARCH_LIMIT} 条）
     */
    @Override
    public List<UserDetailDTO> searchUsersByAi(String keyword, Long deptId, String status, Long operatorId) {
        String kw = StringUtils.trim(keyword);
        String state = StringUtils.trim(status);
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(operatorId);
        Set<Long> visibleDeptIds = scope.filterDeptIds();
        boolean selfOnly = ObjectUtil.isNotNull(visibleDeptIds) && visibleDeptIds.isEmpty();
        if (selfOnly && !scope.selfIncluded()) {
            // 数据范围内既无部门也不含本人：不可见任何用户
            return Collections.emptyList();
        }
        LambdaQueryWrapper<SysUser> lqw = Wrappers.lambdaQuery();
        lqw.select(SysUser::getUserId, SysUser::getDeptId, SysUser::getUserName, SysUser::getNickName,
            SysUser::getUserType, SysUser::getEmail, SysUser::getPhoneNumber, SysUser::getGender,
            SysUser::getStatus, SysUser::getLoginDate, SysUser::getCreateTime);
        if (StringUtils.isNotBlank(kw)) {
            lqw.and(w -> w.like(SysUser::getUserName, kw)
                .or().like(SysUser::getNickName, kw)
                .or().like(SysUser::getPhoneNumber, kw));
        }
        if (ObjectUtil.isNotNull(deptId)) {
            if (!scope.canViewDept(deptId)) {
                throw new ServiceException("没有权限搜索该部门的数据：dept_id=" + deptId);
            }
            lqw.in(SysUser::getDeptId, deptMapper.selectDeptAndChildById(deptId));
        }
        lqw.eq(StringUtils.isNotBlank(state), SysUser::getStatus, state);
        if (selfOnly) {
            // 「仅本人」档且无任何可见部门：只返回本人
            lqw.eq(SysUser::getUserId, operatorId);
        } else if (ObjectUtil.isNotNull(visibleDeptIds)) {
            if (scope.selfIncluded()) {
                lqw.and(w -> w.in(SysUser::getDeptId, visibleDeptIds).or().eq(SysUser::getUserId, operatorId));
            } else {
                lqw.in(SysUser::getDeptId, visibleDeptIds);
            }
        }
        lqw.orderByAsc(SysUser::getUserId);
        // 上限保护：常量拼接，无注入面；超出部分由「搜索」工具提示缩小范围
        lqw.last("limit " + AI_SEARCH_LIMIT);
        List<SysUser> list = userMapper.selectList(lqw);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Map<Long, String> deptNames = selectDeptNamesByAi(StreamUtils.toSet(list, SysUser::getDeptId));
        return list.stream().map(user -> {
            UserDetailDTO dto = BeanUtil.copyProperties(user, UserDetailDTO.class);
            dto.setDeptName(deptNames.get(user.getDeptId()));
            return dto;
        }).toList();
    }

    /**
     * 查询用户详情（AI 工具调用；含所属部门名与角色名，超出操作人数据范围时拒绝）。
     *
     * @param userId     用户ID
     * @param operatorId 操作人用户 ID
     * @return 用户详情；用户不存在时返回 null
     */
    @Override
    public UserDetailDTO selectUserDetailByAi(Long userId, Long operatorId) {
        if (ObjectUtil.isNull(userId)) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        if (ObjectUtil.isNull(user)) {
            return null;
        }
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(operatorId);
        if (!scope.canViewUser(userId, user.getDeptId())) {
            throw new ServiceException("没有权限访问该用户的数据：user_id=" + userId);
        }
        UserDetailDTO dto = BeanUtil.copyProperties(user, UserDetailDTO.class);
        if (ObjectUtil.isNotNull(user.getDeptId())) {
            Map<Long, String> deptNames = selectDeptNamesByAi(Collections.singleton(user.getDeptId()));
            dto.setDeptName(deptNames.get(user.getDeptId()));
        }
        List<Long> roleIds = userRoleMapper.lambda()
            .select(SysUserRole::getRoleId)
            .eq(SysUserRole::getUserId, userId)
            .list()
            .stream()
            .map(SysUserRole::getRoleId)
            .toList();
        if (CollUtil.isEmpty(roleIds)) {
            dto.setRoleNames(Collections.emptyList());
        } else {
            dto.setRoleNames(roleMapper.lambda()
                .select(SysRole::getRoleId, SysRole::getRoleName)
                .in(SysRole::getRoleId, roleIds)
                .orderByAsc(SysRole::getRoleSort)
                .list()
                .stream()
                .map(SysRole::getRoleName)
                .toList());
        }
        return dto;
    }

    /**
     * 新增用户（AI 工具调用）。
     *
     * <p>初始密码取平台配置项 {@code sys.user.initPassword}（与用户导入同一口径），
     * 模型不接触也不产出明文口令；操作人由入参显式落 {@code create_by}。</p>
     *
     * @param dto 用户信息与操作人
     * @return 新增的用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Long createUserByAi(UserSaveDTO dto) {
        String userName = StringUtils.trim(dto.getUserName());
        if (StringUtils.isBlank(userName)) {
            throw new ServiceException("用户账号不能为空");
        }
        String nickName = StringUtils.trim(dto.getNickName());
        if (StringUtils.isBlank(nickName)) {
            throw new ServiceException("用户昵称不能为空");
        }
        SysUserBo bo = new SysUserBo();
        bo.setUserName(userName);
        bo.setNickName(nickName);
        bo.setDeptId(resolveDeptIdByAi(dto));
        // 数据范围：新增用户的归属部门必须落在操作人可写范围内
        if (!dataScopeResolver.resolve(dto.getOperatorUserId()).canWriteDept(bo.getDeptId())) {
            throw new ServiceException("没有权限在该部门下新增用户：dept_id=" + bo.getDeptId());
        }
        bo.setEmail(StringUtils.trimToNull(dto.getEmail()));
        bo.setPhoneNumber(StringUtils.trimToNull(dto.getPhoneNumber()));
        bo.setGender(StringUtils.trimToNull(dto.getGender()));
        bo.setRemark(dto.getRemark());
        bo.setStatus(StringUtils.isBlank(dto.getStatus()) ? SystemConstants.NORMAL : StringUtils.trim(dto.getStatus()));
        checkUserUnique(bo);
        SysUser user = MapstructUtils.convert(bo, SysUser.class);
        user.setPassword(BCrypt.hashpw(initPasswordByAi()));
        user.setCreateBy(dto.getOperatorUserId());
        userMapper.insert(user);
        bindUserRolesByAi(user.getUserId(), dto.getRoleIds(), false);
        bindUserPostsByAi(user.getUserId(), dto.getPostIds(), false);
        return user.getUserId();
    }

    /**
     * 修改用户（AI 工具调用；未提供的字段沿用原值）。
     *
     * <p>不叠加框架的 {@code @DataPermission}，改由 {@link #dataScopeResolver} 按显式操作人解析数据范围
     * （目标用户与调整后的部门都须在范围内）；不改密码，其余校验与 {@code SysUserController#edit} 一致。</p>
     *
     * @param dto 用户信息与操作人
     */
    @CacheEvict(cacheNames = CacheNames.SYS_NICKNAME, key = "#dto.userId")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateUserByAi(UserSaveDTO dto) {
        if (ObjectUtil.isNull(dto.getUserId())) {
            throw new ServiceException("用户 ID 不能为空");
        }
        SysUser oldUser = userMapper.selectById(dto.getUserId());
        if (ObjectUtil.isNull(oldUser)) {
            throw new ServiceException("用户不存在，无法修改");
        }
        checkUserAllowed(dto.getUserId());
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(dto.getOperatorUserId());
        if (!scope.canViewUser(oldUser.getUserId(), oldUser.getDeptId())) {
            throw new ServiceException("没有权限修改该用户的数据：user_id=" + dto.getUserId());
        }
        if (ObjectUtil.isNotNull(dto.getDeptId()) && !scope.canWriteDept(dto.getDeptId())) {
            throw new ServiceException("没有权限把用户调整到该部门：dept_id=" + dto.getDeptId());
        }
        SysUserBo bo = new SysUserBo();
        bo.setUserId(oldUser.getUserId());
        bo.setUserName(StringUtils.isBlank(dto.getUserName()) ? oldUser.getUserName() : StringUtils.trim(dto.getUserName()));
        bo.setNickName(StringUtils.isBlank(dto.getNickName()) ? oldUser.getNickName() : StringUtils.trim(dto.getNickName()));
        bo.setDeptId(ObjectUtil.isNull(dto.getDeptId()) ? oldUser.getDeptId() : dto.getDeptId());
        bo.setEmail(ObjectUtil.isNull(dto.getEmail()) ? oldUser.getEmail() : StringUtils.trim(dto.getEmail()));
        bo.setPhoneNumber(ObjectUtil.isNull(dto.getPhoneNumber()) ? oldUser.getPhoneNumber() : StringUtils.trim(dto.getPhoneNumber()));
        bo.setGender(ObjectUtil.isNull(dto.getGender()) ? oldUser.getGender() : StringUtils.trim(dto.getGender()));
        bo.setRemark(ObjectUtil.isNull(dto.getRemark()) ? oldUser.getRemark() : dto.getRemark());
        bo.setStatus(StringUtils.isBlank(dto.getStatus()) ? oldUser.getStatus() : StringUtils.trim(dto.getStatus()));
        if (ObjectUtil.isNotNull(dto.getDeptId())) {
            requireDeptByAi(dto.getDeptId());
        }
        checkUserUnique(bo);
        SysUser user = MapstructUtils.convert(bo, SysUser.class);
        user.setUpdateBy(dto.getOperatorUserId());
        // 不能走 updateById：其 @DataPermission 处理器需要 Sa-Token 登录上下文（Reactor 线程不可用）
        if (userMapper.updateByAi(user) < 1) {
            throw new ServiceException("修改用户信息失败：" + bo.getUserName());
        }
        // null＝不改绑定；空数组＝清空绑定
        if (dto.getRoleIds() != null) {
            bindUserRolesByAi(oldUser.getUserId(), dto.getRoleIds(), true);
        }
        if (dto.getPostIds() != null) {
            bindUserPostsByAi(oldUser.getUserId(), dto.getPostIds(), true);
        }
    }

    /**
     * 删除用户（AI 工具调用；逻辑删除，同时清理角色与岗位关联，目标须在操作人数据范围内）。
     *
     * @param userId         用户ID
     * @param operatorUserId 操作人用户ID（用于拒绝删除本人与解析数据范围）
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteUserByAi(Long userId, Long operatorUserId) {
        if (ObjectUtil.isNull(userId)) {
            throw new ServiceException("用户 ID 不能为空");
        }
        if (userId.equals(operatorUserId)) {
            throw new ServiceException("当前用户不能删除");
        }
        checkUserAllowed(userId);
        SysUser target = userMapper.selectById(userId);
        if (ObjectUtil.isNull(target)) {
            throw new ServiceException("用户不存在");
        }
        if (!dataScopeResolver.resolve(operatorUserId).canViewUser(userId, target.getDeptId())) {
            throw new ServiceException("没有权限删除该用户的数据：user_id=" + userId);
        }
        // 删除用户与角色关联
        userRoleMapper.lambda().eq(SysUserRole::getUserId, userId).delete();
        // 删除用户与岗位关联
        userPostMapper.lambda().eq(SysUserPost::getUserId, userId).delete();
        if (userMapper.deleteById(userId) < 1) {
            throw new ServiceException("删除用户失败");
        }
    }

    /**
     * 解析新增用户的归属部门（未指定时取操作人所属部门，操作人无部门时取默认部门）。
     *
     * @param dto 用户信息与操作人
     * @return 部门 ID
     */
    private Long resolveDeptIdByAi(UserSaveDTO dto) {
        if (ObjectUtil.isNotNull(dto.getDeptId())) {
            requireDeptByAi(dto.getDeptId());
            return dto.getDeptId();
        }
        Long operatorDeptId = null;
        if (ObjectUtil.isNotNull(dto.getOperatorUserId())) {
            SysUser operator = userMapper.selectById(dto.getOperatorUserId());
            operatorDeptId = ObjectUtil.isNull(operator) ? null : operator.getDeptId();
        }
        Long deptId = ObjectUtil.isNull(operatorDeptId) ? SystemConstants.DEFAULT_DEPT_ID : operatorDeptId;
        requireDeptByAi(deptId);
        return deptId;
    }

    /**
     * 校验部门存在（AI 工具路径不叠加框架 {@code @DataPermission}，仅校验存在性；
     * 数据范围另由 {@link #dataScopeResolver} 判定）。
     *
     * @param deptId 部门ID
     */
    private void requireDeptByAi(Long deptId) {
        if (ObjectUtil.isNull(deptId) || ObjectUtil.isNull(deptMapper.selectById(deptId))) {
            throw new ServiceException("部门不存在：dept_id=" + deptId);
        }
    }

    /**
     * 校验账号 / 手机号 / 邮箱唯一（口径与 {@code SysUserController} 一致）。
     *
     * @param bo 用户信息
     */
    private void checkUserUnique(SysUserBo bo) {
        if (!checkUserNameUnique(bo)) {
            throw new ServiceException("登录账号已存在：" + bo.getUserName());
        }
        if (StringUtils.isNotBlank(bo.getPhoneNumber()) && !checkPhoneUnique(bo)) {
            throw new ServiceException("手机号码已存在：" + bo.getPhoneNumber());
        }
        if (StringUtils.isNotBlank(bo.getEmail()) && !checkEmailUnique(bo)) {
            throw new ServiceException("邮箱账号已存在：" + bo.getEmail());
        }
    }

    /**
     * 取新增用户的初始密码（平台配置项，缺失时用兜底值）。
     *
     * @return 明文初始密码
     */
    private String initPasswordByAi() {
        String initPassword = configService.selectConfigByKey(CONFIG_INIT_PASSWORD);
        return StringUtils.isBlank(initPassword) ? CONFIG_INIT_PASSWORD_DEFAULT : initPassword;
    }

    /**
     * 查询部门名称映射（AI 工具调用；链式构造器，无数据权限注解）。
     *
     * @param deptIds 部门ID集合
     * @return Map，key 为部门 ID，value 为部门名称
     */
    private Map<Long, String> selectDeptNamesByAi(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return Collections.emptyMap();
        }
        return StreamUtils.toMap(deptMapper.lambda()
            .select(SysDept::getDeptId, SysDept::getDeptName)
            .in(SysDept::getDeptId, deptIds)
            .list(), SysDept::getDeptId, SysDept::getDeptName);
    }

    /**
     * 重建用户角色绑定（AI 工具调用）。
     *
     * <p>不使用 {@code insertUserRole}：其内部 {@code roleMapper.selectRoleCount} 带
     * {@code @DataPermission}，在非登录线程会失败；此处改为无注解的链式存在性校验。</p>
     *
     * @param userId 用户ID
     * @param roleIds 角色组（空数组＝清空）
     * @param clear 是否先清理既有绑定
     */
    private void bindUserRolesByAi(Long userId, Long[] roleIds, boolean clear) {
        if (ArrayUtil.isEmpty(roleIds)) {
            if (clear) {
                userRoleMapper.lambda().eq(SysUserRole::getUserId, userId).delete();
            }
            return;
        }
        List<Long> roleList = new ArrayList<>(Arrays.asList(roleIds));
        // 非超级管理员，禁止包含超级管理员角色
        if (!LoginHelper.isSuperAdmin(userId)) {
            roleList.remove(SystemConstants.SUPER_ADMIN_ROLE_ID);
        }
        if (roleList.isEmpty()) {
            throw new ServiceException("不允许为普通用户分配超级管理员角色，请至少选择一个其他角色");
        }
        List<Long> distinctRoles = roleList.stream().distinct().toList();
        Long existCount = roleMapper.lambda().in(SysRole::getRoleId, distinctRoles).count();
        if (ObjectUtil.isNull(existCount) || existCount != distinctRoles.size()) {
            throw new ServiceException("所选角色不存在或已被删除，请刷新后重试");
        }
        if (clear) {
            userRoleMapper.lambda().eq(SysUserRole::getUserId, userId).delete();
        }
        List<SysUserRole> list = distinctRoles.stream().map(roleId -> {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            return userRole;
        }).toList();
        userRoleMapper.insertBatch(list);
    }

    /**
     * 重建用户岗位绑定（AI 工具调用；存在性校验同样规避 {@code @DataPermission}）。
     *
     * @param userId 用户ID
     * @param postIds 岗位组（空数组＝清空）
     * @param clear 是否先清理既有绑定
     */
    private void bindUserPostsByAi(Long userId, Long[] postIds, boolean clear) {
        if (ArrayUtil.isEmpty(postIds)) {
            if (clear) {
                userPostMapper.lambda().eq(SysUserPost::getUserId, userId).delete();
            }
            return;
        }
        List<Long> distinctPosts = Arrays.stream(postIds).distinct().toList();
        Long existCount = postMapper.lambda().in(SysPost::getPostId, distinctPosts).count();
        if (ObjectUtil.isNull(existCount) || existCount != distinctPosts.size()) {
            throw new ServiceException("所选岗位不存在或已被删除，请刷新后重试");
        }
        if (clear) {
            userPostMapper.lambda().eq(SysUserPost::getUserId, userId).delete();
        }
        List<SysUserPost> list = distinctPosts.stream().map(postId -> {
            SysUserPost userPost = new SysUserPost();
            userPost.setUserId(userId);
            userPost.setPostId(postId);
            return userPost;
        }).toList();
        userPostMapper.insertBatch(list);
    }

}
