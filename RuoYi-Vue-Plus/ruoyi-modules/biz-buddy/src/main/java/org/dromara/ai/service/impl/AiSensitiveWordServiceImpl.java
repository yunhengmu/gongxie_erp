package org.dromara.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiSensitiveWord;
import org.dromara.ai.domain.bo.AiSensitiveWordBo;
import org.dromara.ai.domain.vo.AiSensitiveWordVo;
import org.dromara.ai.mapper.AiSensitiveWordMapper;
import org.dromara.ai.service.IAiSensitiveWordService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * AI 敏感词库 Service 实现
 *
 * <p>启用中的词库以 JSON 数组缓存在 Redis（{@link #CACHE_KEY}），安全合规引擎每次校验直接读缓存；
 * 管理面的增删改成功后统一失效缓存，避免脏读。缓存未命中时回源数据库并回填。</p>
 *
 * @author BizBuddy
 */
@Service
@RequiredArgsConstructor
public class AiSensitiveWordServiceImpl implements IAiSensitiveWordService {

    /**
     * Redis 词库缓存键
     */
    private static final String CACHE_KEY = "bizbuddy:ai:safety:word-cache";

    /**
     * 词库缓存时长（较长 TTL，靠增删改主动失效保证一致性）
     */
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    private final AiSensitiveWordMapper baseMapper;

    @Override
    public List<AiSensitiveWord> enabledWords() {
        String cached = RedisUtils.getCacheObject(CACHE_KEY);
        if (StringUtils.isNotBlank(cached)) {
            return JsonUtils.parseArray(cached, AiSensitiveWord.class);
        }
        LambdaQueryWrapper<AiSensitiveWord> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiSensitiveWord::getEnabled, AiSensitiveWord.ENABLED);
        lqw.orderByDesc(AiSensitiveWord::getCreateTime);
        List<AiSensitiveWord> words = baseMapper.selectList(lqw);
        RedisUtils.setCacheObject(CACHE_KEY, JsonUtils.toJsonString(words), CACHE_TTL);
        return words;
    }

    @Override
    public PageResult<AiSensitiveWordVo> queryPage(AiSensitiveWordBo bo, PageQuery pageQuery) {
        Page<AiSensitiveWordVo> page = baseMapper.selectVoPage(pageQuery.build(), buildQueryWrapper(bo));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public AiSensitiveWordVo queryById(Long id) {
        AiSensitiveWordVo vo = baseMapper.selectVoById(id);
        if (vo == null) {
            throw new ServiceException("敏感词不存在或已被删除");
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insert(AiSensitiveWordBo bo) {
        AiSensitiveWord entity = toEntity(bo);
        // 走平台统一主键生成器（容忍时钟回拨），不用 IdWorker 的独立单例
        entity.setId(IdGeneratorUtil.nextLongId());
        boolean success = baseMapper.insert(entity) > 0;
        if (success) {
            refreshCache();
        }
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(AiSensitiveWordBo bo) {
        if (bo.getId() == null) {
            throw new ServiceException("主键不能为空");
        }
        if (baseMapper.selectById(bo.getId()) == null) {
            throw new ServiceException("敏感词不存在或已被删除");
        }
        AiSensitiveWord entity = toEntity(bo);
        entity.setId(bo.getId());
        boolean success = baseMapper.updateById(entity) > 0;
        if (success) {
            refreshCache();
        }
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请选择要删除的敏感词");
        }
        boolean success = baseMapper.deleteByIds(ids) > 0;
        if (success) {
            refreshCache();
        }
        return success;
    }

    @Override
    public void refreshCache() {
        RedisUtils.deleteObject(CACHE_KEY);
    }

    /**
     * 构建敏感词查询条件。
     *
     * @param bo 查询条件
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiSensitiveWord> buildQueryWrapper(AiSensitiveWordBo bo) {
        LambdaQueryWrapper<AiSensitiveWord> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getWord()), AiSensitiveWord::getWord, bo.getWord());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), AiSensitiveWord::getCategory, bo.getCategory());
        lqw.eq(StringUtils.isNotBlank(bo.getEnabled()), AiSensitiveWord::getEnabled, bo.getEnabled());
        lqw.orderByDesc(AiSensitiveWord::getCreateTime);
        return lqw;
    }

    /**
     * 业务对象 → 实体（仅装配业务字段，审计列由自动填充处理）。
     *
     * @param bo 业务对象
     * @return 实体
     */
    private AiSensitiveWord toEntity(AiSensitiveWordBo bo) {
        AiSensitiveWord entity = new AiSensitiveWord();
        entity.setWord(StringUtils.trim(bo.getWord()));
        entity.setIsRegex(bo.getIsRegex());
        entity.setCategory(StringUtils.trim(bo.getCategory()));
        entity.setEnabled(bo.getEnabled());
        entity.setRemark(bo.getRemark());
        return entity;
    }

}