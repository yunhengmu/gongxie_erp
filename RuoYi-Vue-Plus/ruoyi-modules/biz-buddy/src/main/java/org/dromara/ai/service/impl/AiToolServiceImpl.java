package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiAgentTool;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.domain.bo.AiToolBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.ai.domain.vo.AiToolVo;
import org.dromara.ai.mapper.AiAgentToolMapper;
import org.dromara.ai.mapper.AiToolMapper;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.ai.service.IAiToolService;
import org.dromara.ai.service.IAiToolTagService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 业务工具注册 Service 业务层处理
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiToolServiceImpl implements IAiToolService {

    private final AiToolMapper baseMapper;

    private final AiAgentToolMapper agentToolMapper;

    /**
     * MCP 出口（REQ-AI-15）：工具定义变更（改描述/参数、停用、删除）会影响出口已挂载的工具，
     * 故保存后触发一次出口重整，保持「暴露清单」与实际出口一致
     */
    private final IAiMcpServerService mcpServerService;

    /**
     * 工具标签（仅管理面分类与检索）：标签与关联表由标签服务管辖，
     * 本服务只在其上做「保存时覆盖式设置 / 删除时清理关联」，**不反向被标签服务依赖**，故无循环依赖
     */
    private final IAiToolTagService toolTagService;

    @Override
    public AiToolVo queryById(Long id) {
        AiToolVo vo = baseMapper.selectVoById(id);
        if (vo != null) {
            fillTags(List.of(vo));
        }
        return vo;
    }

    @Override
    public PageResult<AiToolVo> queryPageList(AiToolBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<AiTool> lqw = buildQueryWrapper(bo);
        // 标签筛选为「任一命中（OR）」：先由关联表反查工具主键；无命中时直接返回空页，避免无意义的全表查询
        if (bo.getTagIds() != null && !bo.getTagIds().isEmpty()) {
            List<Long> toolIds = toolTagService.toolIdsByTagIds(bo.getTagIds());
            if (toolIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            lqw.in(AiTool::getId, toolIds);
        }
        // 「无标签」筛选：反向排除已打过任一标签的工具
        if (Boolean.TRUE.equals(bo.getNoTag())) {
            List<Long> taggedToolIds = toolTagService.allTaggedToolIds();
            if (!taggedToolIds.isEmpty()) {
                lqw.notIn(AiTool::getId, taggedToolIds);
            }
        }
        Page<AiToolVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        fillTags(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<AiToolVo> queryOptions() {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getStatus, AiTool.STATUS_ENABLED);
        lqw.orderByAsc(AiTool::getToolCode);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(AiToolBo bo) {
        AiTool add = MapstructUtils.convert(bo, AiTool.class);
        add.setToolCode(normalizeCode(add.getToolCode()));
        applyDefaults(add, null);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            // 标签为覆盖式设置（新增时 null 与空集合等价：都是「没有标签」）
            toolTagService.applyToolTags(add.getId(), bo.getTagIds());
            mcpServerService.reconcile();
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(AiToolBo bo) {
        AiTool exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("业务工具不存在或已被删除");
        }
        AiTool update = MapstructUtils.convert(bo, AiTool.class);
        update.setToolCode(normalizeCode(update.getToolCode()));
        if (!exists.getToolCode().equals(update.getToolCode())) {
            throw new ServiceException("工具编码不允许修改（已被智能体绑定）");
        }
        applyDefaults(update, exists);
        validEntityBeforeSave(update);
        boolean flag = baseMapper.updateById(update) > 0;
        if (flag) {
            // 标签留 null 表示不修改；传空集合表示清空全部标签
            if (bo.getTagIds() != null) {
                toolTagService.applyToolTags(bo.getId(), bo.getTagIds());
            }
            mcpServerService.reconcile();
        }
        return flag;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的业务工具");
        }
        List<AiTool> list = baseMapper.selectByIds(ids);
        if (list.size() != ids.size()) {
            throw new ServiceException("您没有删除权限!");
        }
        if (isValid) {
            List<String> codes = list.stream().map(AiTool::getToolCode).toList();
            LambdaQueryWrapper<AiAgentTool> lqw = Wrappers.lambdaQuery();
            lqw.in(AiAgentTool::getToolCode, codes);
            if (agentToolMapper.selectCount(lqw) > 0) {
                throw new ServiceException("该工具已被智能体绑定，请先在智能体定义中解除绑定");
            }
        }
        boolean flag = baseMapper.deleteByIds(ids) > 0;
        if (flag) {
            // 工具删除后清理其标签关联（关联表为物理表，避免残留孤儿行）
            toolTagService.removeByToolIds(ids);
            mcpServerService.reconcile();
        }
        return flag;
    }

    @Override
    public AiTool queryEntity(String toolCode) {
        if (StringUtils.isBlank(toolCode)) {
            return null;
        }
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getToolCode, toolCode);
        return baseMapper.selectOne(lqw);
    }

    @Override
    public boolean exists(String toolCode) {
        return queryEntity(toolCode) != null;
    }

    @Override
    public List<AiTool> queryEnabledByCodes(Collection<String> toolCodes) {
        if (toolCodes == null || toolCodes.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.in(AiTool::getToolCode, toolCodes);
        lqw.eq(AiTool::getStatus, AiTool.STATUS_ENABLED);
        lqw.orderByAsc(AiTool::getToolCode);
        return baseMapper.selectList(lqw);
    }

    /**
     * 校验工具编码格式（与智能体编码同款约束，作为 AgentTool 的 name 使用）。
     *
     * @param toolCode 原始编码
     * @return 规范化后的编码
     */
    private String normalizeCode(String toolCode) {
        String code = StringUtils.trim(toolCode).toLowerCase();
        if (!code.matches("[a-z][a-z0-9_]{1,60}")) {
            throw new ServiceException("工具编码只能包含小写字母、数字与下划线，且以字母开头（2~61 位）");
        }
        return code;
    }

    /**
     * 填充默认值。
     *
     * @param entity 实体
     * @param exists 原实体（新增时为 null）
     */
    private void applyDefaults(AiTool entity, AiTool exists) {
        if (StringUtils.isBlank(entity.getToolType())) {
            entity.setToolType(exists == null ? AiTool.TYPE_READ_ONLY : exists.getToolType());
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(exists == null ? AiTool.STATUS_ENABLED : exists.getStatus());
        }
    }

    /**
     * 构建工具动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiTool> buildQueryWrapper(AiToolBo bo) {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.and(StringUtils.isNotBlank(bo.getKeyword()), w -> w
            .like(AiTool::getToolCode, bo.getKeyword())
            .or().like(AiTool::getToolName, bo.getKeyword()));
        lqw.eq(StringUtils.isNotBlank(bo.getToolType()), AiTool::getToolType, bo.getToolType());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiTool::getStatus, bo.getStatus());
        lqw.orderByAsc(AiTool::getToolCode);
        return lqw;
    }

    /**
     * 填充工具列表 / 详情的标签列（批量查询，避免 N+1）。
     *
     * @param list 工具列表
     */
    private void fillTags(List<AiToolVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Long> toolIds = list.stream().map(AiToolVo::getId).toList();
        Map<Long, List<AiToolTagVo>> tagMap = toolTagService.tagMapByToolIds(toolIds);
        list.forEach(vo -> vo.setTags(tagMap.getOrDefault(vo.getId(), List.of())));
    }

    /**
     * 保存前的数据校验。
     *
     * @param entity 实体数据
     */
    private void validEntityBeforeSave(AiTool entity) {
        LambdaQueryWrapper<AiTool> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiTool::getToolCode, entity.getToolCode());
        lqw.ne(entity.getId() != null, AiTool::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("工具编码已存在：" + entity.getToolCode());
        }
    }

}
