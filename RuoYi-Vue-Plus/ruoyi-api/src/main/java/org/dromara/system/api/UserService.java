package org.dromara.system.api;

import org.dromara.system.api.domain.UserDTO;
import org.dromara.system.api.domain.UserDetailDTO;
import org.dromara.system.api.domain.UserSaveDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 通用 用户服务
 *
 * <p>其中 {@code *ByAi} 系列供 AI 用户工具调用：由调用方显式传入操作人，避免依赖线程上下文
 * （AI 工具执行在 Reactor 线程，Sa-Token 与数据权限上下文均不可用），并在服务内复刻
 * {@code SysUserController} 的业务校验（账号 / 手机号 / 邮箱唯一、角色规则、删除保护），
 * 从而与页面操作保持同一口径。</p>
 *
 * <p><b>数据权限</b>：AI 路径无法使用带 {@code @DataPermission} 的 Mapper 方法，
 * 改由服务实现按显式操作人解析角色数据范围（六档，见 {@code AiDataScopeResolver}）后叠加过滤与校验，
 * 与页面口径一致；超管与「全部数据权限」不限。</p>
 *
 * @author Lion Li
 */
public interface UserService {

    /**
     * 通过用户ID查询用户账户
     *
     * @param userId 用户ID
     * @return 用户账户
     */
    String selectUserNameById(Long userId);

    /**
     * 通过用户ID查询用户昵称
     *
     * @param userId 用户ID
     * @return 用户昵称
     */
    String selectNicknameById(Long userId);

    /**
     * 通过用户ID查询用户昵称
     *
     * @param userIds 用户ID 多个用逗号隔开
     * @return 用户昵称
     */
    String selectNicknameByIds(String userIds);

    /**
     * 通过用户ID查询用户手机号
     *
     * @param userId 用户id
     * @return 用户手机号
     */
    String selectPhonenumberById(Long userId);

    /**
     * 通过用户ID查询用户邮箱
     *
     * @param userId 用户id
     * @return 用户邮箱
     */
    String selectEmailById(Long userId);

    /**
     * 通过用户ID查询用户
     *
     * @param userId 用户id
     * @return 用户列表
     */
    UserDTO selectById(Long userId);

    /**
     * 通过用户ID查询用户列表
     *
     * @param userIds 用户ids
     * @return 用户列表
     */
    List<UserDTO> selectListByIds(Collection<Long> userIds);

    /**
     * 通过角色ID查询用户ID
     *
     * @param roleIds 角色ids
     * @return 用户ids
     */
    List<Long> selectUserIdsByRoleIds(Collection<Long> roleIds);

    /**
     * 通过角色ID查询用户
     *
     * @param roleIds 角色ids
     * @return 用户
     */
    List<UserDTO> selectUsersByRoleIds(Collection<Long> roleIds);

    /**
     * 通过部门ID查询用户
     *
     * @param deptIds 部门ids
     * @return 用户
     */
    List<UserDTO> selectUsersByDeptIds(Collection<Long> deptIds);

    /**
     * 通过岗位ID查询用户
     *
     * @param postIds 岗位ids
     * @return 用户
     */
    List<UserDTO> selectUsersByPostIds(Collection<Long> postIds);

    /**
     * 根据用户 ID 列表查询用户昵称映射关系
     *
     * @param userIds 用户 ID 列表
     * @return Map，其中 key 为用户 ID，value 为对应的用户昵称
     */
    Map<Long, String> selectUserNicksByIds(Collection<Long> userIds);

    /**
     * 按关键词 / 部门 / 状态搜索用户（AI 工具调用；结果按操作人的角色数据范围过滤）。
     *
     * @param keyword    关键词，匹配账号 / 昵称 / 手机号，可空（空则不限）
     * @param deptId     部门 ID，可空（空则不限）；非空时**含其所有下级部门**，且须在操作人数据范围内
     * @param status     账号状态（0正常 1停用），可空（空则不限）
     * @param operatorId 操作人用户 ID（用于解析角色数据范围）
     * @return 用户详情列表（按用户 ID 升序，最多 {@code 200} 条）
     */
    List<UserDetailDTO> searchUsersByAi(String keyword, Long deptId, String status, Long operatorId);

    /**
     * 查询用户详情（AI 工具调用；含所属部门名与角色名，超出操作人数据范围时拒绝）。
     *
     * @param userId     用户ID
     * @param operatorId 操作人用户 ID（用于解析角色数据范围）
     * @return 用户详情；用户不存在时返回 null
     */
    UserDetailDTO selectUserDetailByAi(Long userId, Long operatorId);

    /**
     * 新增用户（AI 工具调用）。
     *
     * <p>校验口径与 {@code SysUserController#add} 一致（账号 / 手机号 / 邮箱唯一、部门存在且在操作人数据范围内）；
     * 初始密码取平台配置项 {@code sys.user.initPassword}（与用户导入同一口径，不由模型提供）；
     * 操作人由入参显式给出并落 {@code create_by}（AI 工具在 Reactor 线程，自动填充取不到登录用户）。</p>
     *
     * @param dto 用户信息与操作人
     * @return 新增的用户ID
     */
    Long createUserByAi(UserSaveDTO dto);

    /**
     * 修改用户（AI 工具调用；未提供的字段沿用原值）。
     *
     * <p>校验口径与 {@code SysUserController#edit} 一致（账号 / 手机号 / 邮箱唯一、不允许操作超级管理员），
     * 并叠加操作人角色数据范围（目标用户与调整后的部门都须在范围内）；
     * **不改密码**（与页面修改一致，密码重置走独立动作）。</p>
     *
     * @param dto 用户信息与操作人
     */
    void updateUserByAi(UserSaveDTO dto);

    /**
     * 删除用户（AI 工具调用；逻辑删除，同时清理角色与岗位关联）。
     *
     * <p>保护口径与 {@code SysUserController#remove} 一致：超级管理员与操作人本人不允许删除。</p>
     *
     * @param userId         用户ID
     * @param operatorUserId 操作人用户ID（用于拒绝删除本人）
     */
    void deleteUserByAi(Long userId, Long operatorUserId);

}
