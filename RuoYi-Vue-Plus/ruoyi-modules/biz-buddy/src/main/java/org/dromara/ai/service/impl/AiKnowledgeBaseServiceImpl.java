package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiChunk;
import org.dromara.ai.domain.AiDocument;
import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiKnowledgeBaseBo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.ai.mapper.AiChunkMapper;
import org.dromara.ai.mapper.AiDocumentMapper;
import org.dromara.ai.mapper.AiKnowledgeBaseMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 知识库 Service 业务层处理
 *
 * @author BizBuddy
 */
@RequiredArgsConstructor
@Service
public class AiKnowledgeBaseServiceImpl implements IAiKnowledgeBaseService {

    /**
     * 向量模型类型
     */
    private static final String TYPE_EMBEDDING = "embedding";

    /**
     * 状态：正常
     */
    private static final String STATUS_NORMAL = "0";

    /**
     * 向量集合名称前缀
     */
    private static final String COLLECTION_PREFIX = "kb_";

    /**
     * 生效范围：公开（全平台可见与可用）
     */
    private static final String SCOPE_ALL = "0";

    /**
     * 生效范围：本部门（owner_dept_id 所在部门）
     */
    private static final String SCOPE_DEPT = "1";

    /**
     * 生效范围：仅本人（创建人）
     */
    private static final String SCOPE_SELF = "2";

    /**
     * 知识库默认切片长度
     */
    private static final int DEFAULT_CHUNK_SIZE = 500;

    /**
     * 知识库默认切片重叠长度
     */
    private static final int DEFAULT_CHUNK_OVERLAP = 50;

    /**
     * 默认召回条数
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 默认相似度阈值
     */
    private static final BigDecimal DEFAULT_SIMILARITY_THRESHOLD = new BigDecimal("0.500");

    private final AiKnowledgeBaseMapper baseMapper;

    private final AiModelConfigMapper modelConfigMapper;

    private final AiDocumentMapper documentMapper;

    private final AiChunkMapper chunkMapper;

    private final UserService userService;

    @Override
    public AiKnowledgeBaseVo queryById(Long id) {
        AiKnowledgeBaseVo vo = baseMapper.selectVoById(id);
        if (vo != null) {
            fillModelName(List.of(vo));
        }
        return vo;
    }

    @Override
    public PageResult<AiKnowledgeBaseVo> queryPageList(AiKnowledgeBaseBo bo, PageQuery pageQuery) {
        Page<AiKnowledgeBaseVo> result = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        fillModelName(result.getRecords());
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<AiKnowledgeBaseVo> queryOptions() {
        LambdaQueryWrapper<AiKnowledgeBase> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiKnowledgeBase::getStatus, STATUS_NORMAL);
        lqw.orderByDesc(AiKnowledgeBase::getUpdateTime);
        return baseMapper.selectVoList(lqw);
    }

    @Override
    public List<AiKnowledgeBaseVo> queryVisibleOptions(Long userId) {
        return baseMapper.selectVoList(buildVisibleWrapper(userId, true));
    }

    @Override
    public List<Long> filterVisibleKbIds(Collection<Long> kbIds, Long userId) {
        return intersectVisible(kbIds, userId, false);
    }

    @Override
    public List<Long> filterRetrievableKbIds(Collection<Long> kbIds, Long userId) {
        // 检索链路额外要求启用：停用库只应从选项下拉消失是既往口径，此处一并收敛（docs/14 第 36 项）
        return intersectVisible(kbIds, userId, true);
    }

    /**
     * 求「待过滤集合」∩「指定用户可见集合」。
     *
     * @param kbIds       待过滤的知识库主键集合
     * @param userId      用户主键
     * @param onlyEnabled 是否只取启用中的知识库
     * @return 交集（保持入参顺序）
     */
    private List<Long> intersectVisible(Collection<Long> kbIds, Long userId, boolean onlyEnabled) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        if (userId == null) {
            // 无法识别提问人时按最严处理：不放行任何知识库
            return List.of();
        }
        Set<Long> visible = new HashSet<>();
        for (AiKnowledgeBase kb : baseMapper.selectList(buildVisibleWrapper(userId, onlyEnabled))) {
            visible.add(kb.getId());
        }
        List<Long> result = new ArrayList<>();
        for (Long kbId : kbIds) {
            if (visible.contains(kbId)) {
                result.add(kbId);
            }
        }
        return result;
    }

    @Override
    public List<Long> listPublicEnabledKbIds() {
        LambdaQueryWrapper<AiKnowledgeBase> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiKnowledgeBase::getStatus, STATUS_NORMAL);
        // 存量数据 visible_scope 为空时按「公开」处理（与 buildVisibleWrapper 口径一致）
        lqw.and(w -> w.eq(AiKnowledgeBase::getVisibleScope, SCOPE_ALL)
            .or(o -> o.isNull(AiKnowledgeBase::getVisibleScope)));
        lqw.orderByAsc(AiKnowledgeBase::getId);
        return baseMapper.selectList(lqw).stream().map(AiKnowledgeBase::getId).toList();
    }

    @Override
    public void validateVisible(Collection<Long> kbIds, Long userId, String scene) {
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        if (userId == null) {
            throw new ServiceException("无法识别当前用户，不能" + scene + "知识库");
        }
        List<Long> visible = filterVisibleKbIds(kbIds, userId);
        if (visible.size() == new HashSet<>(kbIds).size()) {
            return;
        }
        Set<Long> allowed = new HashSet<>(visible);
        List<String> denied = new ArrayList<>();
        for (Long kbId : kbIds) {
            if (!allowed.contains(kbId)) {
                AiKnowledgeBase kb = baseMapper.selectById(kbId);
                denied.add(kb == null ? ("#" + kbId) : kb.getKbName());
            }
        }
        throw new ServiceException("以下知识库不在你的生效范围内，不能" + scene + "：" + String.join("、", denied));
    }

    @Override
    public void validateVisible(Long kbId, Long userId, String scene) {
        if (kbId == null) {
            return;
        }
        validateVisible(List.of(kbId), userId, scene);
    }

    /**
     * 构建「指定用户可见」的知识库查询条件（口径与 {@code ai_agent.visible_scope} 一致）。
     *
     * @param userId         用户主键
     * @param onlyEnabled    是否只取启用中的知识库
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiKnowledgeBase> buildVisibleWrapper(Long userId, boolean onlyEnabled) {
        LambdaQueryWrapper<AiKnowledgeBase> lqw = Wrappers.lambdaQuery();
        if (onlyEnabled) {
            lqw.eq(AiKnowledgeBase::getStatus, STATUS_NORMAL);
        }
        Long deptId = resolveDeptId(userId);
        lqw.and(w -> w.eq(AiKnowledgeBase::getVisibleScope, SCOPE_ALL)
            .or(o -> o.eq(AiKnowledgeBase::getVisibleScope, SCOPE_DEPT).eq(AiKnowledgeBase::getOwnerDeptId, deptId))
            .or(o -> o.eq(AiKnowledgeBase::getVisibleScope, SCOPE_SELF).eq(AiKnowledgeBase::getCreateBy, userId))
            // 存量数据 visible_scope 为空时按「公开」处理，避免历史库因未回填而不可见
            .or(o -> o.isNull(AiKnowledgeBase::getVisibleScope)));
        lqw.orderByDesc(AiKnowledgeBase::getUpdateTime);
        return lqw;
    }

    /**
     * 解析用户部门主键。
     *
     * <p>走 {@link UserService} 而非 Sa-Token 线程上下文：本方法会被对话检索工具在 Reactor 线程调用，
     * 那里没有登录上下文，只有 {@code RuntimeContext.userId}。</p>
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

    @Override
    public Boolean insertByBo(AiKnowledgeBaseBo bo) {
        AiKnowledgeBase add = MapstructUtils.convert(bo, AiKnowledgeBase.class);
        AiModelConfig model = resolveEmbeddingModel(add.getEmbeddingModelId());
        resolveRerank(add);
        add.setKbCode(normalizeCode(add.getKbCode()));
        add.setCollectionName(buildCollectionName(add.getKbCode()));
        add.setDimension(resolveDimension(add.getDimension(), model));
        applyDefaults(add);
        validEntityBeforeSave(add);
        boolean flag = baseMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(AiKnowledgeBaseBo bo) {
        AiKnowledgeBase exists = baseMapper.selectById(bo.getId());
        if (exists == null) {
            throw new ServiceException("知识库不存在或已被删除");
        }
        AiKnowledgeBase update = MapstructUtils.convert(bo, AiKnowledgeBase.class);
        AiModelConfig model = resolveEmbeddingModel(update.getEmbeddingModelId());
        resolveRerank(update);
        update.setKbCode(normalizeCode(update.getKbCode()));
        update.setCollectionName(buildCollectionName(update.getKbCode()));
        update.setDimension(resolveDimension(update.getDimension(), model));
        boolean indexed = hasChunks(exists.getId());
        if (indexed && !exists.getKbCode().equals(update.getKbCode())) {
            throw new ServiceException("该知识库已存在切片数据，不允许修改知识库编码");
        }
        if (indexed && !exists.getEmbeddingModelId().equals(update.getEmbeddingModelId())) {
            throw new ServiceException("该知识库已存在切片数据，不允许更换向量模型；如需更换请先清空文档");
        }
        applyDefaults(update);
        validEntityBeforeSave(update);
        return baseMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            List<AiKnowledgeBase> list = baseMapper.selectByIds(ids);
            if (list.size() != ids.size()) {
                throw new ServiceException("您没有删除权限!");
            }
            for (Long id : ids) {
                LambdaQueryWrapper<AiDocument> lqw = Wrappers.lambdaQuery();
                lqw.eq(AiDocument::getKbId, id);
                if (documentMapper.selectCount(lqw) > 0) {
                    throw new ServiceException("知识库下仍存在文档，请先删除文档后再删除知识库");
                }
            }
        }
        return baseMapper.deleteByIds(ids) > 0;
    }

    @Override
    public AiKnowledgeBase getEnabledKnowledgeBase(Long id) {
        AiKnowledgeBase knowledgeBase = baseMapper.selectById(id);
        if (knowledgeBase == null) {
            throw new ServiceException("知识库不存在或已被删除");
        }
        if (!STATUS_NORMAL.equals(knowledgeBase.getStatus())) {
            throw new ServiceException("知识库已停用：" + knowledgeBase.getKbName());
        }
        return knowledgeBase;
    }

    @Override
    public AiKnowledgeBase queryEntity(Long id) {
        return id == null ? null : baseMapper.selectById(id);
    }

    /**
     * 补充向量模型 / 重排模型名称。
     *
     * @param list 知识库列表
     */
    private void fillModelName(List<AiKnowledgeBaseVo> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> modelIds = new HashSet<>();
        for (AiKnowledgeBaseVo vo : list) {
            if (vo.getEmbeddingModelId() != null) {
                modelIds.add(vo.getEmbeddingModelId());
            }
            if (vo.getRerankModelId() != null) {
                modelIds.add(vo.getRerankModelId());
            }
        }
        if (modelIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = new HashMap<>();
        for (AiModelConfig model : modelConfigMapper.selectByIds(modelIds)) {
            nameMap.put(model.getId(), model.getConfigName());
        }
        list.forEach(vo -> {
            vo.setEmbeddingModelName(nameMap.get(vo.getEmbeddingModelId()));
            vo.setRerankModelName(nameMap.get(vo.getRerankModelId()));
        });
    }

    /**
     * 校验知识库编码格式。
     *
     * @param kbCode 原始编码
     * @return 规范化后的编码
     */
    private String normalizeCode(String kbCode) {
        String code = StringUtils.trim(kbCode).toLowerCase();
        if (!code.matches("[a-z][a-z0-9_]{1,60}")) {
            throw new ServiceException("知识库编码只能包含小写字母、数字与下划线，且以字母开头（2~61 位）");
        }
        return code;
    }

    /**
     * 构建向量集合名称。
     *
     * @param kbCode 知识库编码
     * @return 集合名称
     */
    private String buildCollectionName(String kbCode) {
        return COLLECTION_PREFIX + kbCode;
    }

    /**
     * 校验并解析向量维度。
     *
     * @param dimension 表单维度
     * @param model     向量模型
     * @return 最终维度
     */
    private Integer resolveDimension(Integer dimension, AiModelConfig model) {
        if (dimension == null) {
            if (model.getDimension() == null) {
                throw new ServiceException("所选向量模型未配置向量维度，请先到「模型配置」中补充");
            }
            return model.getDimension();
        }
        if (model.getDimension() != null && !model.getDimension().equals(dimension)) {
            throw new ServiceException("向量维度与所选向量模型不一致（模型为 " + model.getDimension() + "）");
        }
        return dimension;
    }

    /**
     * 校验向量模型可用性。
     *
     * @param modelId 向量模型主键
     * @return 向量模型实体
     */
    private AiModelConfig resolveEmbeddingModel(Long modelId) {
        if (modelId == null) {
            throw new ServiceException("向量模型不能为空");
        }
        AiModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException("向量模型不存在或已被删除");
        }
        if (!TYPE_EMBEDDING.equalsIgnoreCase(StringUtils.trim(model.getModelType()))) {
            throw new ServiceException("所选模型不是向量模型：" + model.getConfigName());
        }
        if (!STATUS_NORMAL.equals(model.getStatus())) {
            throw new ServiceException("所选向量模型已停用：" + model.getConfigName());
        }
        return model;
    }

    /**
     * 校验并归一化重排配置：仅当「启用重排」时才要求并校验重排模型（存在 / 类型 rerank / 未停用）；
     * 未启用则一律清空模型绑定，避免留下「开关关了却还挂着模型」的脏数据。
     *
     * @param entity 知识库实体（就地改写）
     */
    private void resolveRerank(AiKnowledgeBase entity) {
        if (!"1".equals(StringUtils.trim(entity.getRerankEnabled()))) {
            entity.setRerankEnabled("0");
            entity.setRerankModelId(null);
            return;
        }
        Long modelId = entity.getRerankModelId();
        if (modelId == null) {
            throw new ServiceException("启用检索重排时必须选择重排模型");
        }
        AiModelConfig model = modelConfigMapper.selectById(modelId);
        if (model == null) {
            throw new ServiceException("重排模型不存在或已被删除");
        }
        if (!AiModelConfig.TYPE_RERANK.equalsIgnoreCase(StringUtils.trim(model.getModelType()))) {
            throw new ServiceException("所选模型不是重排模型：" + model.getConfigName());
        }
        if (!STATUS_NORMAL.equals(model.getStatus())) {
            throw new ServiceException("所选重排模型已停用：" + model.getConfigName());
        }
    }

    /**
     * 填充默认切分与检索参数。
     *
     * @param entity 知识库实体
     */
    private void applyDefaults(AiKnowledgeBase entity) {
        if (entity.getChunkSize() == null) {
            entity.setChunkSize(DEFAULT_CHUNK_SIZE);
        }
        if (entity.getChunkOverlap() == null) {
            entity.setChunkOverlap(DEFAULT_CHUNK_OVERLAP);
        }
        if (entity.getRetrievalTopK() == null) {
            entity.setRetrievalTopK(DEFAULT_TOP_K);
        }
        if (entity.getSimilarityThreshold() == null) {
            entity.setSimilarityThreshold(DEFAULT_SIMILARITY_THRESHOLD);
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus(STATUS_NORMAL);
        }
        if (StringUtils.isBlank(entity.getVisibleScope())) {
            entity.setVisibleScope(SCOPE_ALL);
        }
        // 「本部门」需落归属部门；其余档位不写（保持空，避免残留旧部门导致误判）
        if (SCOPE_DEPT.equals(entity.getVisibleScope()) && entity.getOwnerDeptId() == null) {
            entity.setOwnerDeptId(LoginHelper.getDeptId());
        }
    }

    /**
     * 判断知识库是否已产生切片数据。
     *
     * @param kbId 知识库主键
     * @return 是否存在切片
     */
    private boolean hasChunks(Long kbId) {
        LambdaQueryWrapper<AiChunk> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiChunk::getKbId, kbId);
        return chunkMapper.selectCount(lqw) > 0;
    }

    /**
     * 构建知识库动态查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiKnowledgeBase> buildQueryWrapper(AiKnowledgeBaseBo bo) {
        LambdaQueryWrapper<AiKnowledgeBase> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getKbName()), AiKnowledgeBase::getKbName, bo.getKbName());
        lqw.like(StringUtils.isNotBlank(bo.getKbCode()), AiKnowledgeBase::getKbCode, bo.getKbCode());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), AiKnowledgeBase::getStatus, bo.getStatus());
        lqw.orderByDesc(AiKnowledgeBase::getCreateTime);
        return lqw;
    }

    /**
     * 保存前的数据校验。
     *
     * @param entity 实体数据
     */
    private void validEntityBeforeSave(AiKnowledgeBase entity) {
        if (entity.getChunkSize() != null && entity.getChunkOverlap() != null
            && entity.getChunkOverlap() >= entity.getChunkSize()) {
            throw new ServiceException("切片重叠长度必须小于切片长度");
        }
        LambdaQueryWrapper<AiKnowledgeBase> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiKnowledgeBase::getKbCode, entity.getKbCode());
        lqw.ne(entity.getId() != null, AiKnowledgeBase::getId, entity.getId());
        if (baseMapper.selectCount(lqw) > 0) {
            throw new ServiceException("知识库编码已存在：" + entity.getKbCode());
        }
    }

}