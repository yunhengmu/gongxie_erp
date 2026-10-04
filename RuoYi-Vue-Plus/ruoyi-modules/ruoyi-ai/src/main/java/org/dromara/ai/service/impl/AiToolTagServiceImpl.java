package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiToolTag;
import org.dromara.ai.domain.AiToolTagRel;
import org.dromara.ai.domain.bo.AiToolTagBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.mapper.AiToolTagMapper;
import org.dromara.ai.mapper.AiToolTagRelMapper;
import org.dromara.ai.service.IAiToolTagService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务工具标签 Service 业务层处理
 *
 * <p>标签与关联同属本服务管辖（关联表为物理表、先删后插）；本服务**不反向依赖**
 * {@code IAiToolService}，由工具服务在保存 / 删除时调用本服务完成关联维护，避免循环依赖。</p>
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiToolTagServiceImpl implements IAiToolTagService {

    private final AiToolTagMapper baseMapper;

    private final AiToolTagRelMapper relMapper;

    /**
     * 仅用于批量操作的「工具存在性」校验（Mapper 级只读依赖，不经 Service，避免与工具服务形成循环依赖）
     */
    private final AiToolMapper toolMapper;

    @Override
    public PageResult<AiToolTagVo> queryPageList(AiToolTagBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiToolTag> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getKeyword()), AiToolTag::getTagName, bo.getKeyword());
        lqw.orderByAsc(AiToolTag::getTagName);
        Page<AiToolTagVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillUseCount(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<AiToolTagVo> queryOptions() {
        LambdaQueryWrapper<AiToolTag> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(AiToolTag::getTagName);
        List<AiToolTagVo> list = baseMapper.selectVoList(lqw);
        fillUseCount(list);
        return list;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiToolTagBo bo) {
        AiToolTag add = MapstructUtils.convert(bo, AiToolTag.class);
        add.setTagName(StringUtils.trim(bo.getTagName()));
        validTagNameUnique(add);
        return baseMapper.insert(add) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiToolTagBo bo) {
        AiToolTag exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("标签不存在或已被删除");
        }
        AiToolTag update = MapstructUtils.convert(bo, AiToolTag.class);
        update.setTagName(StringUtils.trim(bo.getTagName()));
        validTagNameUnique(update);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的标签");
        }
        if (baseMapper.selectByIds(ids).size() != ids.size()) {
            throw new ServiceException("标签不存在或已被删除");
        }
        // 标签是分类标记：删除即级联解除关联（不做「被使用时拒绝删除」）
        relMapper.delete(Wrappers.<AiToolTagRel>lambdaQuery().in(AiToolTagRel::getTagId, ids));
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public List<Long> tagIdsOfTool(Long toolId) {
        if (toolId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiToolTagRel> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiToolTagRel::getToolId, toolId);
        return relMapper.selectList(lqw).stream().map(AiToolTagRel::getTagId).distinct().toList();
    }

    @Override
    public Map<Long, List<AiToolTagVo>> tagMapByToolIds(Collection<Long> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<AiToolTagRel> relLqw = Wrappers.lambdaQuery();
        relLqw.in(AiToolTagRel::getToolId, toolIds);
        List<AiToolTagRel> rels = relMapper.selectList(relLqw);
        if (rels.isEmpty()) {
            return Map.of();
        }
        Set<Long> tagIds = rels.stream().map(AiToolTagRel::getTagId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AiToolTagVo> tagById = baseMapper.selectVoByIds(tagIds).stream()
            .collect(Collectors.toMap(AiToolTagVo::getId, vo -> vo, (a, b) -> a));
        Map<Long, List<AiToolTagVo>> result = new LinkedHashMap<>();
        for (AiToolTagRel rel : rels) {
            AiToolTagVo tag = tagById.get(rel.getTagId());
            if (tag == null) {
                continue;
            }
            result.computeIfAbsent(rel.getToolId(), k -> new ArrayList<>()).add(tag);
        }
        return result;
    }

    @Override
    public List<Long> toolIdsByTagIds(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<AiToolTagRel> lqw = Wrappers.lambdaQuery();
        lqw.select(AiToolTagRel::getToolId);
        lqw.in(AiToolTagRel::getTagId, tagIds);
        return relMapper.selectList(lqw).stream().map(AiToolTagRel::getToolId).distinct().toList();
    }

    @Override
    public List<Long> allTaggedToolIds() {
        LambdaQueryWrapper<AiToolTagRel> lqw = Wrappers.lambdaQuery();
        lqw.select(AiToolTagRel::getToolId);
        return relMapper.selectList(lqw).stream().map(AiToolTagRel::getToolId).distinct().toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyToolTags(Long toolId, Collection<Long> tagIds) {
        if (toolId == null) {
            return;
        }
        relMapper.delete(Wrappers.<AiToolTagRel>lambdaQuery().eq(AiToolTagRel::getToolId, toolId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = distinctIds(tagIds);
        if (distinct.isEmpty()) {
            return;
        }
        if (baseMapper.selectByIds(distinct).size() != distinct.size()) {
            throw new ServiceException("所选标签不存在或已被删除，请刷新后重试");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long tagId : distinct) {
            AiToolTagRel rel = new AiToolTagRel();
            rel.setToolId(toolId);
            rel.setTagId(tagId);
            rel.setCreateTime(now);
            relMapper.insert(rel);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applyTagsToTools(Collection<Long> toolIds, Collection<Long> tagIds) {
        Set<Long> tools = distinctIds(toolIds);
        Set<Long> tags = distinctIds(tagIds);
        if (tools.isEmpty()) {
            throw new ServiceException("请先勾选要操作的工具");
        }
        if (tags.isEmpty()) {
            throw new ServiceException("请先选择标签");
        }
        validToolsExist(tools);
        if (baseMapper.selectByIds(tags).size() != tags.size()) {
            throw new ServiceException("所选标签不存在或已被删除，请刷新后重试");
        }
        // 追加语义：只补缺失的关联，工具已有标签（含本次未勾选的标签）一律不动
        LambdaQueryWrapper<AiToolTagRel> existLqw = Wrappers.lambdaQuery();
        existLqw.in(AiToolTagRel::getToolId, tools);
        existLqw.in(AiToolTagRel::getTagId, tags);
        Set<String> exists = relMapper.selectList(existLqw).stream()
            .map(rel -> rel.getToolId() + ":" + rel.getTagId())
            .collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        int inserted = 0;
        for (Long toolId : tools) {
            for (Long tagId : tags) {
                if (exists.contains(toolId + ":" + tagId)) {
                    continue;
                }
                AiToolTagRel rel = new AiToolTagRel();
                rel.setToolId(toolId);
                rel.setTagId(tagId);
                rel.setCreateTime(now);
                relMapper.insert(rel);
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeTagsFromTools(Collection<Long> toolIds, Collection<Long> tagIds) {
        Set<Long> tools = distinctIds(toolIds);
        Set<Long> tags = distinctIds(tagIds);
        if (tools.isEmpty()) {
            throw new ServiceException("请先勾选要操作的工具");
        }
        if (tags.isEmpty()) {
            throw new ServiceException("请先选择标签");
        }
        validToolsExist(tools);
        return relMapper.delete(Wrappers.<AiToolTagRel>lambdaQuery()
            .in(AiToolTagRel::getToolId, tools)
            .in(AiToolTagRel::getTagId, tags));
    }

    @Override
    public void removeByToolIds(Collection<Long> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return;
        }
        relMapper.delete(Wrappers.<AiToolTagRel>lambdaQuery().in(AiToolTagRel::getToolId, toolIds));
    }

    /**
     * 去重（保序）并剔除空值。
     *
     * @param ids 原始主键集合
     * @return 去重后的主键集合
     */
    private static Set<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 校验工具主键均存在（逻辑删除行按不存在处理）。
     *
     * @param toolIds 工具主键集合
     */
    private void validToolsExist(Set<Long> toolIds) {
        if (toolMapper.selectByIds(toolIds).size() != toolIds.size()) {
            throw new ServiceException("所选工具不存在或已被删除，请刷新后重试");
        }
    }

    /**
     * 校验标签名称唯一（逻辑删除下由服务层保证）。
     *
     * @param entity 待保存实体
     */
    private void validTagNameUnique(AiToolTag entity) {
        if (StringUtils.isBlank(entity.getTagName())) {
            throw new ServiceException("标签名称不能为空");
        }
        LambdaQueryWrapper<AiToolTag> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiToolTag::getTagName, entity.getTagName());
        lqw.ne(entity.getId() != null, AiToolTag::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("标签名称已存在：" + entity.getTagName());
        }
    }

    /**
     * 填充「使用该标签的工具数」。
     *
     * @param list 标签列表
     */
    private void fillUseCount(List<AiToolTagVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> tagIds = list.stream().map(AiToolTagVo::getId).toList();
        LambdaQueryWrapper<AiToolTagRel> lqw = Wrappers.lambdaQuery();
        lqw.in(AiToolTagRel::getTagId, tagIds);
        Map<Long, Long> counts = relMapper.selectList(lqw).stream()
            .collect(Collectors.groupingBy(AiToolTagRel::getTagId, Collectors.counting()));
        list.forEach(vo -> vo.setUseCount(counts.getOrDefault(vo.getId(), 0L)));
    }

}