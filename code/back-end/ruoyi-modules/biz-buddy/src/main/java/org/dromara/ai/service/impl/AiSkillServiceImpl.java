package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgentSkill;
import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.AiSkillResource;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiSkillBo;
import org.dromara.ai.domain.bo.AiSkillResourceBo;
import org.dromara.ai.domain.vo.AiSkillOptionVo;
import org.dromara.ai.domain.vo.AiSkillVo;
import org.dromara.ai.mapper.AiAgentSkillMapper;
import org.dromara.ai.mapper.AiSkillMapper;
import org.dromara.ai.mapper.AiSkillResourceMapper;
import org.dromara.ai.mapper.AiToolCallLogMapper;
import org.dromara.ai.service.IAiSkillService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 技能 Service 业务层处理
 *
 * <p>技能正文以 SKILL.md 落库（frontmatter 由 name / description 自动拼装）；
 * 使用计数聚合自工具调用明细中的技能加载调用；陈旧归档状态按「最近使用 / 创建时间 + 阈值」计算，
 * 阈值与框架 {@code SkillCuratorConfig} 默认值（30 / 90 天）对齐。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiSkillServiceImpl implements IAiSkillService {

    /**
     * AgentScope 注册的技能加载工具名（入参含 skillId，用于聚合使用计数）
     */
    private static final String LOADER_TOOL_NAME = "load_skill_through_path";

    /**
     * 技能读取工具名（与框架 SkillUsageMiddleware 的 VIEW_TOOL_NAMES 对齐）
     */
    private static final String READER_TOOL_NAME = "read_skill";

    /**
     * 陈旧阈值（天），与框架 SkillCuratorConfig 默认值对齐
     */
    private static final int STALE_AFTER_DAYS = 30;

    /**
     * 归档阈值（天），与框架 SkillCuratorConfig 默认值对齐
     */
    private static final int ARCHIVE_AFTER_DAYS = 90;

    /**
     * 生命周期状态：活跃
     */
    private static final String STATE_ACTIVE = "active";

    /**
     * 生命周期状态：陈旧
     */
    private static final String STATE_STALE = "stale";

    /**
     * 生命周期状态：待归档
     */
    private static final String STATE_ARCHIVED = "archived";

    /**
     * 生命周期状态：已停用
     */
    private static final String STATE_DISABLED = "disabled";

    private final AiSkillMapper baseMapper;

    private final AiSkillResourceMapper resourceMapper;

    private final AiAgentSkillMapper agentSkillMapper;

    private final AiToolCallLogMapper toolCallLogMapper;

    private final UserService userService;

    private final DeptService deptService;

    @Override
    public PageResult<AiSkillVo> queryPageList(AiSkillBo bo, PageQuery pageQuery) {
        Page<AiSkillVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        List<AiSkillVo> records = result.getRecords();
        fillDisplayFields(records);
        return PageResult.build(records, result.getTotal());
    }

    @Override
    public AiSkillVo queryById(Long id) {
        AiSkillVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("技能不存在或已被删除");
        }
        fillDisplayFields(List.of(vo));
        LambdaQueryWrapper<AiSkillResource> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSkillResource::getId, id);
        lqw.orderByAsc(AiSkillResource::getResourcePath);
        vo.setResources(resourceMapper.selectVoList(lqw));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiSkillBo bo) {
        AiSkill add = MapstructUtils.convert(bo, AiSkill.class);
        String name = normalizeName(bo.getName());
        add.setName(name);
        add.setDescription(StringUtils.trim(bo.getDescription()));
        add.setSkillContent(composeSkillContent(name, add.getDescription(), bo.getSkillBody()));
        add.setSource(AiSkill.SOURCE_BIZBUDDY);
        applyDefaults(add);
        validNameUnique(add);
        LocalDateTime now = LocalDateTime.now();
        add.setCreatedAt(now);
        add.setUpdatedAt(now);
        add.setCreateBy(LoginHelper.getUserId());
        add.setCreateDept(LoginHelper.getDeptId());
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            saveResources(add.getId(), bo.getResources());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiSkillBo bo) {
        AiSkill exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("技能不存在或已被删除");
        }
        String name = normalizeName(bo.getName());
        if (!exists.getName().equals(name)) {
            throw new ServiceException("技能标识不允许修改（已作为 AgentScope 的 skillId 使用）");
        }
        AiSkill update = MapstructUtils.convert(bo, AiSkill.class);
        update.setName(name);
        update.setDescription(StringUtils.trim(bo.getDescription()));
        update.setSkillContent(composeSkillContent(name, update.getDescription(), bo.getSkillBody()));
        applyDefaults(update);
        update.setUpdatedAt(LocalDateTime.now());
        update.setUpdateBy(LoginHelper.getUserId());
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            replaceResources(update.getId(), bo.getResources());
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的技能");
        }
        List<AiSkill> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("技能不存在或已被删除");
        }
        LambdaQueryWrapper<AiAgentSkill> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSkill::getSkillId, ids);
        if (agentSkillMapper.selectCount(lqw) > 0) {
            throw new ServiceException("该技能已被智能体绑定，请先在智能体定义中解除绑定");
        }
        // 物理删除：参考资料随 ai_skill_resource 的外键级联清理
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public List<AiSkillOptionVo> queryVisibleOptions(Long userId) {
        return baseMapper.selectList(buildVisibleWrapper(userId, true)).stream()
            .map(skill -> MapstructUtils.convert(skill, AiSkillOptionVo.class))
            .toList();
    }

    @Override
    public List<Long> filterVisibleSkillIds(Collection<Long> skillIds, Long userId) {
        if (skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        if (userId == null) {
            // 无法识别提问人时按最严处理：不放行任何技能
            return List.of();
        }
        Set<Long> visible = new HashSet<>();
        for (AiSkill skill : baseMapper.selectList(buildVisibleWrapper(userId, true))) {
            visible.add(skill.getId());
        }
        List<Long> result = new ArrayList<>();
        for (Long skillId : new LinkedHashSet<>(skillIds)) {
            if (visible.contains(skillId)) {
                result.add(skillId);
            }
        }
        return result;
    }

    @Override
    public void validateVisible(Collection<Long> skillIds, Long userId, String scene) {
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        if (userId == null) {
            throw new ServiceException("无法识别当前用户，不能" + scene + "技能");
        }
        List<Long> visible = filterVisibleSkillIds(skillIds, userId);
        if (visible.size() == new LinkedHashSet<>(skillIds).size()) {
            return;
        }
        Set<Long> allowed = new HashSet<>(visible);
        List<String> denied = new ArrayList<>();
        for (Long skillId : new LinkedHashSet<>(skillIds)) {
            if (!allowed.contains(skillId)) {
                AiSkill skill = baseMapper.selectById(skillId);
                denied.add(skill == null ? ("#" + skillId) : skill.getName());
            }
        }
        throw new ServiceException("以下技能不在你的生效范围内，不能" + scene + "：" + String.join("、", denied));
    }

    @Override
    public AiSkill getEnabledSkill(Long id) {
        AiSkill skill = id == null ? null : baseMapper.selectById(id);
        if (skill == null) {
            throw new ServiceException("技能不存在或已被删除");
        }
        if (!AiSkill.STATUS_ENABLED.equals(skill.getStatus())) {
            throw new ServiceException("技能已停用：" + skill.getName());
        }
        return skill;
    }

    @Override
    public List<String> resolveRuntimeSkillFilterKeys(Long agentId, Long userId) {
        return resolveRuntimeSkillFilterKeys(agentId, null, userId);
    }

    @Override
    public List<String> resolveRuntimeSkillFilterKeys(Long agentId, Collection<Long> extraSkillIds,
                                                      Long userId) {
        List<Long> bound = mergeSkillIds(queryBoundSkillIds(agentId), extraSkillIds);
        List<Long> visible = bound.isEmpty() ? List.of() : filterVisibleSkillIds(bound, userId);
        return buildFilterKeys(visible);
    }

    @Override
    public List<String> resolveAgentSkillFilterKeys(Long agentId) {
        return resolveAgentSkillFilterKeys(agentId, null);
    }

    @Override
    public List<String> resolveAgentSkillFilterKeys(Long agentId, Collection<Long> extraSkillIds) {
        return buildFilterKeys(mergeSkillIds(queryBoundSkillIds(agentId), extraSkillIds));
    }

    /**
     * 合并「智能体绑定技能」与「场景包附加技能」并去重（保持绑定在前、附加在后）。
     *
     * @param bound        智能体绑定技能主键
     * @param extraSkillIds 场景包附加技能主键（可为空）
     * @return 合并去重后的技能主键
     */
    private List<Long> mergeSkillIds(List<Long> bound, Collection<Long> extraSkillIds) {
        Set<Long> merged = new LinkedHashSet<>();
        if (bound != null) {
            merged.addAll(bound);
        }
        if (extraSkillIds != null) {
            for (Long skillId : extraSkillIds) {
                if (skillId != null) {
                    merged.add(skillId);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /**
     * 由技能主键集合构造过滤键（每个技能给出 name 与 name_source 两种形式）。
     *
     * <p>只放行启用中的技能；技能来源已收口为单一来源（本项目数据库），
     * 故过滤键只有「绑定 ∪ 资源集」这一路。</p>
     *
     * @param skillIds 技能主键集合
     * @return 过滤键集合
     */
    private List<String> buildFilterKeys(Collection<Long> skillIds) {
        Set<String> keys = new LinkedHashSet<>();
        if (skillIds != null && !skillIds.isEmpty()) {
            for (AiSkill skill : baseMapper.selectByIds(skillIds)) {
                if (!AiSkill.STATUS_ENABLED.equals(skill.getStatus())) {
                    continue;
                }
                keys.add(skill.getName());
                keys.add(skill.getName() + "_" + skill.getSource());
            }
        }
        return new ArrayList<>(keys);
    }

    @Override
    public List<Long> queryBoundSkillIds(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiAgentSkill> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentSkill::getAgentId, agentId);
        lqw.orderByAsc(AiAgentSkill::getId);
        return agentSkillMapper.selectList(lqw).stream().map(AiAgentSkill::getSkillId).toList();
    }

    @Override
    public Map<Long, List<Long>> queryAgentSkillMap(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiAgentSkill> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSkill::getAgentId, agentIds);
        lqw.orderByAsc(AiAgentSkill::getId);
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (AiAgentSkill entity : agentSkillMapper.selectList(lqw)) {
            result.computeIfAbsent(entity.getAgentId(), key -> new ArrayList<>()).add(entity.getSkillId());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceAgentBindings(Long agentId, Collection<Long> skillIds) {
        LambdaQueryWrapper<AiAgentSkill> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiAgentSkill::getAgentId, agentId);
        agentSkillMapper.delete(lqw);
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        for (Long skillId : new LinkedHashSet<>(skillIds)) {
            AiAgentSkill entity = new AiAgentSkill();
            entity.setAgentId(agentId);
            entity.setSkillId(skillId);
            agentSkillMapper.insert(entity);
        }
    }

    @Override
    public void deleteAgentBindings(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<AiAgentSkill> lqw = Wrappers.lambdaQuery();
        lqw.in(AiAgentSkill::getAgentId, agentIds);
        agentSkillMapper.delete(lqw);
    }

    /**
     * 校验技能标识格式并规范化。
     *
     * @param raw 原始标识
     * @return 规范化后的标识
     */
    private String normalizeName(String raw) {
        String name = StringUtils.trim(raw).toLowerCase();
        if (!name.matches("^[a-z][a-z0-9_-]{1,60}$")) {
            throw new ServiceException("技能标识只能包含小写字母、数字、下划线或短横线，且以字母开头（2~61 位）");
        }
        return name;
    }

    /**
     * 拼装 SKILL.md 全文（YAML frontmatter + Markdown 正文）。
     *
     * @param name        技能标识
     * @param description 技能用途说明
     * @param body        正文
     * @return SKILL.md 全文
     */
    private String composeSkillContent(String name, String description, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(quoteYaml(name)).append('\n');
        sb.append("description: ").append(quoteYaml(description)).append('\n');
        sb.append("---\n\n");
        if (StringUtils.isNotBlank(body)) {
            sb.append(body.strip()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从 SKILL.md 全文剥离 frontmatter，得到正文。
     *
     * @param content SKILL.md 全文
     * @return 正文
     */
    private String extractSkillBody(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        String text = content;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end >= 0) {
                return text.substring(Math.min(end + 4, text.length())).strip();
            }
        }
        return text.strip();
    }

    /**
     * 生成 YAML 双引号标量（转义反斜杠、双引号与换行），避免说明中的冒号等字符破坏 frontmatter。
     *
     * @param value 原始值
     * @return YAML 标量
     */
    private String quoteYaml(String value) {
        String text = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n' || c == '\r') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 填充默认值。
     *
     * @param entity 实体
     */
    private void applyDefaults(AiSkill entity) {
        if (StringUtils.isBlank(entity.getVisibleScope())) {
            entity.setVisibleScope(AiSkill.SCOPE_ALL);
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(AiSkill.STATUS_ENABLED);
        }
        if (AiSkill.SCOPE_DEPT.equals(entity.getVisibleScope()) && entity.getOwnerDeptId() == null) {
            entity.setOwnerDeptId(LoginHelper.getDeptId());
        }
    }

    /**
     * 校验技能标识唯一。
     *
     * @param entity 待保存实体
     */
    private void validNameUnique(AiSkill entity) {
        LambdaQueryWrapper<AiSkill> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSkill::getName, entity.getName());
        lqw.ne(entity.getId() != null, AiSkill::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("技能标识已存在：" + entity.getName());
        }
    }

    /**
     * 保存参考资料（新增技能时）。
     *
     * @param skillId   技能主键
     * @param resources 参考资料集合
     */
    private void saveResources(Long skillId, List<AiSkillResourceBo> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AiSkillResource entity : normalizeResources(skillId, resources)) {
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            resourceMapper.insert(entity);
        }
    }

    /**
     * 覆盖式保存参考资料（修改技能时）。
     *
     * @param skillId   技能主键
     * @param resources 参考资料集合
     */
    private void replaceResources(Long skillId, List<AiSkillResourceBo> resources) {
        LambdaQueryWrapper<AiSkillResource> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSkillResource::getId, skillId);
        resourceMapper.delete(lqw);
        saveResources(skillId, resources);
    }

    /**
     * 参考资料去重与路径校验。
     *
     * @param skillId   技能主键
     * @param resources 参考资料集合
     * @return 规范化后的实体集合
     */
    private List<AiSkillResource> normalizeResources(Long skillId, List<AiSkillResourceBo> resources) {
        Map<String, AiSkillResource> result = new LinkedHashMap<>();
        for (AiSkillResourceBo bo : resources) {
            String path = StringUtils.trim(bo.getResourcePath());
            if (StringUtils.isBlank(path)) {
                throw new ServiceException("参考资料路径不能为空");
            }
            if (path.startsWith("/") || path.contains("..") || path.length() > 500) {
                throw new ServiceException("参考资料路径必须是技能内的相对路径（不以 / 开头、不含 ..，且不超过 500 字符）：" + path);
            }
            if (path.equals("SKILL.md")) {
                throw new ServiceException("SKILL.md 由技能正文承载，不能作为参考资料");
            }
            AiSkillResource entity = new AiSkillResource();
            entity.setId(skillId);
            entity.setResourcePath(path);
            entity.setResourceContent(bo.getResourceContent() == null ? "" : bo.getResourceContent());
            result.put(path, entity);
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 构建技能动态查询条件（管理页全量）。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiSkill> buildQueryWrapper(AiSkillBo bo) {
        LambdaQueryWrapper<AiSkill> lqw = Wrappers.lambdaQuery();
        lqw.and(StringUtils.isNotBlank(bo.getKeyword()), w -> w
            .like(AiSkill::getName, bo.getKeyword())
            .or().like(AiSkill::getDescription, bo.getKeyword()));
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiSkill::getStatus, bo.getStatus());
        lqw.orderByDesc(AiSkill::getUpdatedAt);
        return lqw;
    }

    /**
     * 构建「指定用户可见」的技能查询条件（口径与 {@code ai_agent.visible_scope} 一致）。
     *
     * @param userId      用户主键
     * @param onlyEnabled 是否只取启用中的技能
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiSkill> buildVisibleWrapper(Long userId, boolean onlyEnabled) {
        LambdaQueryWrapper<AiSkill> lqw = Wrappers.lambdaQuery();
        if (onlyEnabled) {
            lqw.eq(AiSkill::getStatus, AiSkill.STATUS_ENABLED);
        }
        Long deptId = resolveDeptId(userId);
        lqw.and(w -> w.eq(AiSkill::getVisibleScope, AiSkill.SCOPE_ALL)
            .or(o -> o.eq(AiSkill::getVisibleScope, AiSkill.SCOPE_DEPT).eq(AiSkill::getOwnerDeptId, deptId))
            .or(o -> o.eq(AiSkill::getVisibleScope, AiSkill.SCOPE_SELF).eq(AiSkill::getCreateBy, userId))
            // 存量数据 visible_scope 为空时按「公开」处理，避免历史技能因未回填而不可见
            .or(o -> o.isNull(AiSkill::getVisibleScope)));
        lqw.orderByDesc(AiSkill::getUpdatedAt);
        return lqw;
    }

    /**
     * 解析用户部门主键（走 UserService，避免在 Reactor 线程读取登录上下文）。
     *
     * @param userId 用户主键
     * @return 部门主键；无法解析时返回 null
     */
    private Long resolveDeptId(Long userId) {
        if (userId == null) {
            return null;
        }
        UserDTO user = userService.selectById(userId);
        return user == null ? null : user.getDeptId();
    }

    /**
     * 填充部门名称、创建人昵称、参考资料数量、使用计数与陈旧归档状态。
     *
     * @param list 技能列表
     */
    private void fillDisplayFields(List<AiSkillVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> deptIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> skillIds = new HashSet<>();
        for (AiSkillVo vo : list) {
            if (vo.getOwnerDeptId() != null) {
                deptIds.add(vo.getOwnerDeptId());
            }
            if (vo.getCreateBy() != null) {
                userIds.add(vo.getCreateBy());
            }
            if (vo.getId() != null) {
                skillIds.add(vo.getId());
            }
            vo.setSkillBody(extractSkillBody(vo.getSkillContent()));
        }
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of() : deptService.selectDeptNamesByIds(deptIds);
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.selectUserNicksByIds(userIds);
        Map<Long, Integer> resourceCounts = countResources(skillIds);
        Map<String, UsageStat> usageStats = queryUsageStats(list);
        LocalDateTime now = LocalDateTime.now();
        for (AiSkillVo vo : list) {
            // 注意：不可变 Map（Map.of）对 null 键会抛 NPE，取值前必须先判空
            vo.setOwnerDeptName(vo.getOwnerDeptId() == null ? null : deptNames.get(vo.getOwnerDeptId()));
            vo.setCreateByName(vo.getCreateBy() == null ? null : userNames.get(vo.getCreateBy()));
            vo.setResourceCount(vo.getId() == null ? 0 : resourceCounts.getOrDefault(vo.getId(), 0));
            UsageStat stat = vo.getName() == null ? null : usageStats.get(vo.getName());
            int useCount = stat == null ? 0 : stat.count();
            vo.setUseCount(useCount);
            vo.setLastUsedAt(stat == null ? null : stat.lastUsedAt());
            vo.setLifecycleState(resolveLifecycleState(vo.getStatus(),
                stat == null ? null : stat.lastUsedAt(), vo.getCreatedAt(), now));
        }
    }

    /**
     * 统计各技能的参考资料数量。
     *
     * @param skillIds 技能主键集合
     * @return 技能主键 → 数量
     */
    private Map<Long, Integer> countResources(Set<Long> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiSkillResource> lqw = Wrappers.lambdaQuery();
        lqw.in(AiSkillResource::getId, skillIds);
        Map<Long, Integer> counts = new HashMap<>();
        for (AiSkillResource entity : resourceMapper.selectList(lqw)) {
            counts.merge(entity.getId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 聚合「技能使用计数」：统计技能加载 / 读取工具被调用的次数与最近时间。
     *
     * <p>框架的 {@code SkillUsageStore} 只记录 {@code created_by=agent} 的技能，本项目维护的技能
     * 不会计入，故此处以工具调用明细为准。注意框架的 skillId 是
     * {@code AgentSkill#getSkillId()} = {@code name + "_" + source}（如 {@code demo_bizbuddy}），
     * 故按「名称」或「名称_前缀」两种形式匹配。</p>
     *
     * @param skills 技能列表（取名称用于匹配）
     * @return 技能标识 → 用量统计
     */
    private Map<String, UsageStat> queryUsageStats(Collection<AiSkillVo> skills) {
        if (skills == null || skills.isEmpty()) {
            return Map.of();
        }
        // skillId → 技能名称（同时接受 name 与 name_source 两种写法）
        Map<String, String> keyToName = new HashMap<>();
        for (AiSkillVo vo : skills) {
            if (StringUtils.isBlank(vo.getName())) {
                continue;
            }
            keyToName.put(vo.getName(), vo.getName());
            keyToName.put(vo.getName() + "_" + AiSkill.SOURCE_BIZBUDDY, vo.getName());
        }
        LambdaQueryWrapper<AiToolCallLog> lqw = Wrappers.lambdaQuery();
        lqw.in(AiToolCallLog::getToolName, List.of(LOADER_TOOL_NAME, READER_TOOL_NAME));
        Map<String, UsageStat> stats = new HashMap<>();
        for (AiToolCallLog log : toolCallLogMapper.selectList(lqw)) {
            String rawSkillId = parseSkillId(log.getToolInput());
            String skillName = rawSkillId == null ? null : keyToName.get(rawSkillId);
            if (skillName == null) {
                continue;
            }
            UsageStat stat = stats.get(skillName);
            LocalDateTime time = log.getCreateTime();
            if (stat == null) {
                stats.put(skillName, new UsageStat(1, time));
            } else {
                LocalDateTime last = stat.lastUsedAt();
                if (time != null && (last == null || time.isAfter(last))) {
                    last = time;
                }
                stats.put(skillName, new UsageStat(stat.count() + 1, last));
            }
        }
        return stats;
    }

    /**
     * 从工具入参 JSON 中解析 skillId。
     *
     * @param toolInput 入参 JSON
     * @return 技能标识；解析失败时返回 null
     */
    private String parseSkillId(String toolInput) {
        if (StringUtils.isBlank(toolInput)) {
            return null;
        }
        try {
            Map<?, ?> map = JsonUtils.parseObject(toolInput, Map.class);
            Object value = map == null ? null : map.get("skillId");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算陈旧归档状态：停用优先，其次按「最近使用（无使用则创建时间）」的时长判定。
     *
     * @param status      技能状态
     * @param lastUsedAt  最近使用时间（可为空）
     * @param createdAt   创建时间
     * @param now         当前时间
     * @return 生命周期状态
     */
    private String resolveLifecycleState(String status, LocalDateTime lastUsedAt,
                                         LocalDateTime createdAt, LocalDateTime now) {
        if (!AiSkill.STATUS_ENABLED.equals(status)) {
            return STATE_DISABLED;
        }
        LocalDateTime baseline = lastUsedAt != null ? lastUsedAt : createdAt;
        if (baseline == null) {
            return STATE_ACTIVE;
        }
        long days = Duration.between(baseline, now).toDays();
        if (days >= ARCHIVE_AFTER_DAYS) {
            return STATE_ARCHIVED;
        }
        return days >= STALE_AFTER_DAYS ? STATE_STALE : STATE_ACTIVE;
    }

    /**
     * 技能用量统计。
     *
     * @param count      调用次数
     * @param lastUsedAt 最近调用时间
     */
    private record UsageStat(int count, LocalDateTime lastUsedAt) {

    }

}
