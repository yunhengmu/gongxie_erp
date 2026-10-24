package org.dromara.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.*;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.query.LambdaQueryBuilder;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.common.redis.utils.CacheUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDTO;
import org.dromara.system.api.domain.DeptDetailDTO;
import org.dromara.system.api.domain.DeptSaveDTO;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysPostService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 部门管理 服务实现
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysDeptServiceImpl implements ISysDeptService, DeptService {

    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final ISysPostService postService;

    /**
     * AI 路径的角色数据范围解析器（AI 工具跑在 Reactor 线程，不能用 {@code @DataPermission}）
     */
    private final AiDataScopeResolver dataScopeResolver;

    /**
     * 分页查询部门管理数据
     *
     * @param dept      部门信息
     * @param pageQuery 分页对象
     * @return 部门信息集合
     */
    @Override
    public PageResult<SysDeptVo> selectPageDeptList(SysDeptBo dept, PageQuery pageQuery) {
        Page<SysDeptVo> page = deptMapper.selectPageDeptList(pageQuery.build(), buildQueryWrapper(dept));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 查询部门管理数据
     *
     * @param dept 部门信息
     * @return 部门信息集合
     */
    @Override
    public List<SysDeptVo> selectDeptList(SysDeptBo dept) {
        LambdaQueryWrapper<SysDept> lqw = buildQueryWrapper(dept);
        return deptMapper.selectDeptList(lqw);
    }

    /**
     * 查询部门树结构信息
     *
     * @param bo 部门信息
     * @return 部门树信息集合
     */
    @Override
    public List<Tree<Long>> selectDeptTreeList(SysDeptBo bo) {
        LambdaQueryWrapper<SysDept> lqw = buildQueryWrapper(bo);
        List<SysDeptVo> depts = deptMapper.selectDeptList(lqw);
        return buildDeptTreeSelect(depts);
    }

    /**
     * 构造部门列表查询条件。
     *
     * @param bo 部门筛选条件
     * @return 包含树级过滤、状态、分类和时间区间的查询包装器
     */
    private LambdaQueryWrapper<SysDept> buildQueryWrapper(SysDeptBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryBuilder<SysDept> builder = QueryBuilder.lambda(SysDept.class)
            .eqIfPresent(SysDept::getDeptId, bo.getDeptId())
            .eqIfPresent(SysDept::getParentId, bo.getParentId())
            .likeIfText(SysDept::getDeptName, bo.getDeptName())
            .likeIfText(SysDept::getDeptCategory, bo.getDeptCategory())
            .eqIfText(SysDept::getStatus, bo.getStatus())
            .betweenParams(SysDept::getCreateTime, params, "beginTime", "endTime")
            .orderByAsc(SysDept::getAncestors, SysDept::getParentId, SysDept::getOrderNum, SysDept::getDeptId);
        if (ObjectUtil.isNotNull(bo.getBelongDeptId())) {
            //部门树搜索
            builder.and(x -> {
                List<Long> deptIds = deptMapper.selectDeptAndChildById(bo.getBelongDeptId());
                x.in(SysDept::getDeptId, deptIds);
            });
        }
        return builder.build();
    }

    /**
     * 构建前端所需要下拉树结构
     *
     * @param depts 部门列表
     * @return 下拉树结构列表
     */
    @Override
    public List<Tree<Long>> buildDeptTreeSelect(List<SysDeptVo> depts) {
        if (CollUtil.isEmpty(depts)) {
            return CollUtil.newArrayList();
        }
        return TreeBuildUtils.buildMultiRoot(
            depts,
            SysDeptVo::getDeptId,
            SysDeptVo::getParentId,
            (node, treeNode) -> treeNode
                .setId(node.getDeptId())
                .setParentId(node.getParentId())
                .setName(node.getDeptName())
                .setWeight(node.getOrderNum())
                .putExtra("disabled", SystemConstants.DISABLE.equals(node.getStatus()))
        );
    }

    /**
     * 根据角色ID查询部门树信息
     *
     * @param roleId 角色ID
     * @return 选中部门列表
     */
    @Override
    public List<Long> selectDeptListByRoleId(Long roleId) {
        SysRole role = roleMapper.selectById(roleId);
        return deptMapper.selectDeptListByRoleId(roleId, role.getDeptCheckStrictly());
    }

    /**
     * 根据部门ID查询信息
     *
     * @param deptId 部门ID
     * @return 部门信息
     */
    @Cacheable(cacheNames = CacheNames.SYS_DEPT, key = "#deptId")
    @Override
    public SysDeptVo selectDeptById(Long deptId) {
        SysDeptVo dept = deptMapper.selectVoById(deptId);
        if (ObjectUtil.isNull(dept)) {
            return null;
        }
        SysDeptVo parentDept = deptMapper.lambda()
            .select(SysDept::getDeptName)
            .eq(SysDept::getDeptId, dept.getParentId())
            .voOne();
        dept.setParentName(ObjectUtils.notNullGetter(parentDept, SysDeptVo::getDeptName));
        return dept;
    }

    /**
     * 按部门主键集合查询部门基础信息。
     *
     * @param deptIds 部门主键集合
     * @return 部门基础信息列表
     */
    @Override
    public List<SysDeptVo> selectDeptByIds(Collection<Long> deptIds) {
        return deptMapper.selectDeptList(deptMapper.lambda()
            .select(SysDept::getDeptId, SysDept::getDeptName, SysDept::getLeader)
            .eq(SysDept::getStatus, SystemConstants.NORMAL)
            .in(CollUtil.isNotEmpty(deptIds), SysDept::getDeptId, deptIds)
            .build());
    }

    /**
     * 通过部门ID查询部门名称
     *
     * @param deptIds 部门ID串逗号分隔
     * @return 部门名称串逗号分隔
     */
    @Override
    public String selectDeptNameByIds(String deptIds) {
        List<String> list = new ArrayList<>();
        for (Long id : StringUtils.splitTo(deptIds, Convert::toLong)) {
            SysDeptVo vo = SpringUtils.getAopProxy(this).selectDeptById(id);
            if (ObjectUtil.isNotNull(vo)) {
                list.add(vo.getDeptName());
            }
        }
        return StringUtils.joinComma(list);
    }

    /**
     * 根据部门ID查询部门负责人
     *
     * @param deptId 部门ID，用于指定需要查询的部门
     * @return 返回该部门的负责人ID
     */
    @Override
    public Long selectDeptLeaderById(Long deptId) {
        SysDeptVo vo = SpringUtils.getAopProxy(this).selectDeptById(deptId);
        return ObjectUtil.isNull(vo) ? null : vo.getLeader();
    }

    /**
     * 查询部门
     *
     * @return 部门列表
     */
    @Override
    public List<DeptDTO> selectDeptsByList() {
        List<SysDeptVo> list = deptMapper.selectDeptList(deptMapper.lambda()
            .select(SysDept::getDeptId, SysDept::getDeptName, SysDept::getParentId)
            .eq(SysDept::getStatus, SystemConstants.NORMAL)
            .build());
        return BeanUtil.copyToList(list, DeptDTO.class);
    }

    /**
     * 根据ID查询所有子部门数（正常状态）
     *
     * @param deptId 部门ID
     * @return 子部门数
     */
    @Override
    public long selectNormalChildrenDeptById(Long deptId) {
        return deptMapper.lambda()
            .eq(SysDept::getStatus, SystemConstants.NORMAL)
            .findInSet(deptId, SysDept::getAncestors)
            .count();
    }

    /**
     * 是否存在子节点
     *
     * @param deptId 部门ID
     * @return 结果
     */
    @Override
    public boolean hasChildByDeptId(Long deptId) {
        return deptMapper.lambda().eq(SysDept::getParentId, deptId).exists();
    }

    /**
     * 查询部门是否存在用户
     *
     * @param deptId 部门ID
     * @return 结果 true 存在 false 不存在
     */
    @Override
    public boolean checkDeptExistUser(Long deptId) {
        return userMapper.lambda().eq(SysUser::getDeptId, deptId).exists();
    }

    /**
     * 校验部门名称是否唯一
     *
     * @param dept 部门信息
     * @return 结果
     */
    @Override
    public boolean checkDeptNameUnique(SysDeptBo dept) {
        boolean exist = deptMapper.lambda()
            .eq(SysDept::getDeptName, dept.getDeptName())
            .eq(SysDept::getParentId, dept.getParentId())
            .neIfPresent(SysDept::getDeptId, dept.getDeptId())
            .exists();
        return !exist;
    }

    /**
     * 校验部门是否有数据权限
     *
     * @param deptId 部门id
     */
    @Override
    public void checkDeptDataScope(Long deptId) {
        if (ObjectUtil.isNull(deptId)) {
            return;
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (deptMapper.countDeptById(deptId) == 0) {
            throw new ServiceException("没有权限访问部门数据！");
        }
    }

    /**
     * 新增保存部门信息
     *
     * @param bo 部门信息
     * @return 结果
     */
    @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    @Override
    public int insertDept(SysDeptBo bo) {
        SysDept info = requireNormalParent(bo.getParentId());
        SysDept dept = MapstructUtils.convert(bo, SysDept.class);
        dept.setAncestors(info.getAncestors() + StringUtils.SEPARATOR + dept.getParentId());
        return deptMapper.insert(dept);
    }

    /**
     * 校验父部门存在且为正常状态（新增子部门的前置条件）。
     *
     * @param parentId 父部门ID
     * @return 父部门实体
     */
    private SysDept requireNormalParent(Long parentId) {
        SysDept info = deptMapper.selectById(parentId);
        // 如果父节点不存在或不为正常状态,则不允许新增子节点
        if (ObjectUtil.isNull(info)) {
            throw new ServiceException("父部门不存在");
        }
        if (!SystemConstants.NORMAL.equals(info.getStatus())) {
            throw new ServiceException("部门停用，不允许新增");
        }
        return info;
    }

    /**
     * 修改保存部门信息
     *
     * @param bo 部门信息
     * @return 结果
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT, key = "#bo.deptId"),
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    })
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateDept(SysDeptBo bo) {
        return doUpdateDept(bo, true);
    }

    /**
     * 修改部门（核心逻辑）。
     *
     * @param bo             部门信息
     * @param checkDataScope 变更上级部门时是否校验新上级的数据权限
     *                       （页面操作需校验；AI 工具运行在 Reactor 线程无登录上下文，跳过）
     * @return 结果
     */
    private int doUpdateDept(SysDeptBo bo, boolean checkDataScope) {
        SysDept dept = MapstructUtils.convert(bo, SysDept.class);
        SysDept oldDept = deptMapper.selectById(dept.getDeptId());
        if (ObjectUtil.isNull(oldDept)) {
            throw new ServiceException("部门不存在，无法修改");
        }
        if (!oldDept.getParentId().equals(dept.getParentId())) {
            if (checkDataScope) {
                // 如果是新父部门 则校验是否具有新父部门权限 避免越权
                this.checkDeptDataScope(dept.getParentId());
            }
            SysDept newParentDept = deptMapper.selectById(dept.getParentId());
            if (ObjectUtil.isNotNull(newParentDept)) {
                String newAncestors = newParentDept.getAncestors() + StringUtils.SEPARATOR + newParentDept.getDeptId();
                String oldAncestors = oldDept.getAncestors();
                dept.setAncestors(newAncestors);
                updateDeptChildren(dept.getDeptId(), newAncestors, oldAncestors);
            }
        } else {
            dept.setAncestors(oldDept.getAncestors());
        }
        int result = deptMapper.updateById(dept);
        // 如果部门状态为启用，且部门祖级列表不为空，且部门祖级列表不等于根部门祖级列表（如果部门祖级列表不等于根部门祖级列表，则说明存在上级部门）
        if (SystemConstants.NORMAL.equals(dept.getStatus())
            && StringUtils.isNotEmpty(dept.getAncestors())
            && !StringUtils.equals(SystemConstants.ROOT_DEPT_ANCESTORS, dept.getAncestors())) {
            // 如果该部门是启用状态，则启用该部门的所有上级部门
            updateParentDeptStatusNormal(dept);
        }
        return result;
    }

    /**
     * 修改该部门的父级部门状态
     *
     * @param dept 当前部门
     */
    private void updateParentDeptStatusNormal(SysDept dept) {
        String ancestors = dept.getAncestors();
        Long[] deptIds = Convert.toLongArray(ancestors);
        deptMapper.lambda()
            .set(SysDept::getStatus, SystemConstants.NORMAL)
            .in(SysDept::getDeptId, Arrays.asList(deptIds))
            .update();
    }

    /**
     * 修改子元素关系
     *
     * @param deptId       被修改的部门ID
     * @param newAncestors 新的父ID集合
     * @param oldAncestors 旧的父ID集合
     */
    private void updateDeptChildren(Long deptId, String newAncestors, String oldAncestors) {
        List<SysDept> children = deptMapper.lambda().findInSet(deptId, SysDept::getAncestors).list();
        List<SysDept> list = new ArrayList<>();
        for (SysDept child : children) {
            SysDept dept = new SysDept();
            dept.setDeptId(child.getDeptId());
            dept.setAncestors(StringUtils.replaceOnce(child.getAncestors(), oldAncestors, newAncestors));
            list.add(dept);
        }
        if (CollUtil.isNotEmpty(list)) {
            if (deptMapper.updateBatchById(list)) {
                list.forEach(dept -> CacheUtils.evict(CacheNames.SYS_DEPT, dept.getDeptId()));
            }
        }
    }

    /**
     * 删除部门管理信息
     *
     * @param deptId 部门ID
     * @return 结果
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT, key = "#deptId"),
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    })
    @Override
    public int deleteDeptById(Long deptId) {
        return deptMapper.deleteById(deptId);
    }


    /**
     * 根据部门 ID 列表查询部门名称映射关系
     *
     * @param deptIds 部门 ID 列表
     * @return Map，其中 key 为部门 ID，value 为对应的部门名称
     */
    @Override
    public Map<Long, String> selectDeptNamesByIds(Collection<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return Collections.emptyMap();
        }
        List<SysDept> list = deptMapper.lambda()
            .select(SysDept::getDeptId, SysDept::getDeptName)
            .in(SysDept::getDeptId, deptIds)
            .list();
        return StreamUtils.toMap(list, SysDept::getDeptId, SysDept::getDeptName);
    }

    /**
     * 按关键词 / 状态搜索部门（AI 工具调用；结果按操作人角色数据范围过滤）。
     *
     * <p>本方法**不走**带 {@code @DataPermission} 的 {@code selectDeptList}：AI 工具执行在 Reactor 线程，
     * 数据权限处理器取不到登录用户（{@code LoginHelper} 线程上下文为空）会直接失败；
     * 故查询一律使用无注解的链式构造器，**数据范围由 {@link #dataScopeResolver} 按显式操作人解析后叠加**，
     * 功能权限由工具侧按操作人菜单权限校验。</p>
     *
     * @param keyword    部门名称关键词，可空（空则不限）
     * @param status     部门状态（0正常 1停用），可空（空则不限）
     * @param operatorId 操作人用户 ID
     * @return 部门详情列表（按上级 / 显示顺序排序）
     */
    @Override
    public List<DeptDetailDTO> searchDeptsByAi(String keyword, String status, Long operatorId) {
        String kw = StringUtils.trim(keyword);
        String state = StringUtils.trim(status);
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(operatorId);
        Set<Long> visibleDeptIds = scope.filterDeptIds();
        // 数据范围内没有任何部门（如「仅本人」档）：直接返回空，避免 IN () 的非法 SQL
        if (ObjectUtil.isNotNull(visibleDeptIds) && visibleDeptIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysDept> list = deptMapper.lambda()
            .select(SysDept::getDeptId, SysDept::getParentId, SysDept::getDeptName, SysDept::getDeptCategory,
                SysDept::getOrderNum, SysDept::getLeader, SysDept::getPhone, SysDept::getEmail,
                SysDept::getStatus, SysDept::getCreateTime)
            .like(StringUtils.isNotBlank(kw), SysDept::getDeptName, kw)
            .eq(StringUtils.isNotBlank(state), SysDept::getStatus, state)
            .in(ObjectUtil.isNotNull(visibleDeptIds), SysDept::getDeptId, visibleDeptIds)
            .orderByAsc(SysDept::getParentId)
            .orderByAsc(SysDept::getOrderNum)
            .orderByAsc(SysDept::getDeptId)
            .list();
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        Set<Long> parentIds = StreamUtils.toSet(list, SysDept::getParentId);
        Map<Long, String> parentNames = parentIds.isEmpty() ? Collections.emptyMap() : StreamUtils.toMap(
            deptMapper.lambda()
                .select(SysDept::getDeptId, SysDept::getDeptName)
                .in(SysDept::getDeptId, parentIds)
                .list(),
            SysDept::getDeptId, SysDept::getDeptName);
        return list.stream().map(dept -> {
            DeptDetailDTO dto = BeanUtil.copyProperties(dept, DeptDetailDTO.class);
            dto.setParentName(parentNames.get(dept.getParentId()));
            return dto;
        }).toList();
    }

    /**
     * 查询部门详情（AI 工具调用；超出操作人数据范围时拒绝）。
     *
     * <p>数据范围判定先于存在性查询，避免把范围外部门的「是否存在」泄露给调用方；
     * 读单点豁免操作人本人所属部门（与「个人中心」一致，供「我在哪个部门」类问题使用）。</p>
     *
     * @param deptId     部门ID
     * @param operatorId 操作人用户 ID
     * @return 部门详情；部门不存在时返回 null
     */
    @Override
    public DeptDetailDTO selectDeptDetailByAi(Long deptId, Long operatorId) {
        if (ObjectUtil.isNull(deptId)) {
            return null;
        }
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(operatorId);
        if (!scope.canViewDept(deptId)) {
            throw new ServiceException("没有权限访问该部门的数据：dept_id=" + deptId);
        }
        SysDept dept = deptMapper.selectById(deptId);
        if (ObjectUtil.isNull(dept)) {
            return null;
        }
        DeptDetailDTO dto = BeanUtil.copyProperties(dept, DeptDetailDTO.class);
        if (ObjectUtil.isNotNull(dept.getParentId())) {
            SysDept parent = deptMapper.selectById(dept.getParentId());
            dto.setParentName(ObjectUtil.isNull(parent) ? null : parent.getDeptName());
        }
        return dto;
    }

    /**
     * 新增部门（AI 工具调用）。
     *
     * <p>校验口径与 {@code SysDeptController#add} 一致（父部门存在且正常、同级名称唯一、父部门在操作人数据范围内）；
     * 操作人由入参显式给出并落 {@code create_by}（AI 工具在 Reactor 线程，自动填充取不到登录用户）。</p>
     *
     * @param dto 部门信息与操作人
     * @return 新增的部门ID
     */
    @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    @Override
    public Long createDeptByAi(DeptSaveDTO dto) {
        if (ObjectUtil.isNull(dto.getParentId())) {
            throw new ServiceException("上级部门 ID 不能为空");
        }
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(dto.getOperatorUserId());
        if (!scope.canWriteDept(dto.getParentId())) {
            throw new ServiceException("没有权限在上级部门下新增部门：parent_id=" + dto.getParentId());
        }
        String deptName = StringUtils.trim(dto.getDeptName());
        if (StringUtils.isBlank(deptName)) {
            throw new ServiceException("部门名称不能为空");
        }
        SysDeptBo bo = new SysDeptBo();
        bo.setParentId(dto.getParentId());
        bo.setDeptName(deptName);
        bo.setDeptCategory(dto.getDeptCategory());
        bo.setOrderNum(ObjectUtil.isNull(dto.getOrderNum()) ? 0 : dto.getOrderNum());
        bo.setLeader(dto.getLeader());
        bo.setPhone(dto.getPhone());
        bo.setEmail(dto.getEmail());
        bo.setStatus(StringUtils.isBlank(dto.getStatus()) ? SystemConstants.NORMAL : StringUtils.trim(dto.getStatus()));
        if (!checkDeptNameUnique(bo)) {
            throw new ServiceException("部门名称已存在：" + bo.getDeptName());
        }
        SysDept parent = requireNormalParent(bo.getParentId());
        SysDept dept = MapstructUtils.convert(bo, SysDept.class);
        dept.setAncestors(parent.getAncestors() + StringUtils.SEPARATOR + dept.getParentId());
        dept.setCreateBy(dto.getOperatorUserId());
        deptMapper.insert(dept);
        return dept.getDeptId();
    }

    /**
     * 修改部门（AI 工具调用；未提供的字段沿用原值）。
     *
     * <p>校验口径与 {@code SysDeptController#edit} 一致（名称唯一、上级不能是自己、停用前无未停用子部门与在用用户），
     * 并叠加操作人角色数据范围（**目标部门与调整后的上级部门都须落在范围内**）。</p>
     *
     * @param dto 部门信息与操作人
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT, key = "#dto.deptId"),
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    })
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateDeptByAi(DeptSaveDTO dto) {
        if (ObjectUtil.isNull(dto.getDeptId())) {
            throw new ServiceException("部门 ID 不能为空");
        }
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(dto.getOperatorUserId());
        if (!scope.canWriteDept(dto.getDeptId())) {
            throw new ServiceException("没有权限修改该部门的数据：dept_id=" + dto.getDeptId());
        }
        SysDept oldDept = deptMapper.selectById(dto.getDeptId());
        if (ObjectUtil.isNull(oldDept)) {
            throw new ServiceException("部门不存在，无法修改");
        }
        SysDeptBo bo = new SysDeptBo();
        bo.setDeptId(oldDept.getDeptId());
        bo.setParentId(ObjectUtil.isNull(dto.getParentId()) ? oldDept.getParentId() : dto.getParentId());
        // 仅在「调整上级」时校验新上级的范围：原名 / 属性修改不因父部门在范围外而被拒
        if (ObjectUtil.isNotNull(dto.getParentId()) && !scope.canWriteDept(dto.getParentId())) {
            throw new ServiceException("没有权限把部门移动到该上级部门：parent_id=" + dto.getParentId());
        }
        bo.setDeptName(StringUtils.isBlank(dto.getDeptName()) ? oldDept.getDeptName() : StringUtils.trim(dto.getDeptName()));
        bo.setDeptCategory(ObjectUtil.isNull(dto.getDeptCategory()) ? oldDept.getDeptCategory() : dto.getDeptCategory());
        bo.setOrderNum(ObjectUtil.isNull(dto.getOrderNum()) ? oldDept.getOrderNum() : dto.getOrderNum());
        bo.setLeader(ObjectUtil.isNull(dto.getLeader()) ? oldDept.getLeader() : dto.getLeader());
        bo.setPhone(ObjectUtil.isNull(dto.getPhone()) ? oldDept.getPhone() : dto.getPhone());
        bo.setEmail(ObjectUtil.isNull(dto.getEmail()) ? oldDept.getEmail() : dto.getEmail());
        bo.setStatus(StringUtils.isBlank(dto.getStatus()) ? oldDept.getStatus() : StringUtils.trim(dto.getStatus()));
        if (!checkDeptNameUnique(bo)) {
            throw new ServiceException("部门名称已存在：" + bo.getDeptName());
        }
        if (bo.getParentId().equals(bo.getDeptId())) {
            throw new ServiceException("上级部门不能是自己");
        }
        if (SystemConstants.DISABLE.equals(bo.getStatus())) {
            if (selectNormalChildrenDeptById(bo.getDeptId()) > 0) {
                throw new ServiceException("该部门包含未停用的子部门，不能停用");
            }
            if (checkDeptExistUser(bo.getDeptId())) {
                throw new ServiceException("该部门下存在已分配用户，不能停用");
            }
        }
        doUpdateDept(bo, false);
    }

    /**
     * 删除部门（AI 工具调用；含默认部门 / 下级部门 / 用户 / 岗位保护，目标须在操作人数据范围内）。
     *
     * <p>保护口径与 {@code SysDeptController#remove} 一致。</p>
     *
     * @param deptId     部门ID
     * @param operatorId 操作人用户 ID
     */
    @Caching(evict = {
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT, key = "#deptId"),
        @CacheEvict(cacheNames = CacheNames.SYS_DEPT_AND_CHILD, allEntries = true)
    })
    @Override
    public void deleteDeptByAi(Long deptId, Long operatorId) {
        if (ObjectUtil.isNull(deptId)) {
            throw new ServiceException("部门 ID 不能为空");
        }
        AiDataScopeResolver.AiDataScope scope = dataScopeResolver.resolve(operatorId);
        if (!scope.canWriteDept(deptId)) {
            throw new ServiceException("没有权限删除该部门的数据：dept_id=" + deptId);
        }
        if (deptMapper.selectById(deptId) == null) {
            throw new ServiceException("部门不存在");
        }
        if (SystemConstants.DEFAULT_DEPT_ID.equals(deptId)) {
            throw new ServiceException("默认部门，不允许删除");
        }
        if (hasChildByDeptId(deptId)) {
            throw new ServiceException("存在下级部门，不允许删除");
        }
        if (checkDeptExistUser(deptId)) {
            throw new ServiceException("部门存在用户，不允许删除");
        }
        if (postService.countPostByDeptId(deptId) > 0) {
            throw new ServiceException("部门存在岗位，不允许删除");
        }
        deptMapper.deleteById(deptId);
    }

}
