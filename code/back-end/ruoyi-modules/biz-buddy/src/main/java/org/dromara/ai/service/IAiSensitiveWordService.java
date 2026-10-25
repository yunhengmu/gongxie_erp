package org.dromara.ai.service;

import org.dromara.ai.domain.AiSensitiveWord;
import org.dromara.ai.domain.bo.AiSensitiveWordBo;
import org.dromara.ai.domain.vo.AiSensitiveWordVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * AI 敏感词库 Service 接口
 *
 * <p>读取侧供安全合规引擎装配启用中的词库（带 Redis 缓存，增删改后失效）；
 * 写入侧供管理面维护词库。</p>
 *
 * @author BizBuddy
 */
public interface IAiSensitiveWordService {

    /**
     * 查询启用中的敏感词（带 Redis 缓存，未命中回源数据库）。
     *
     * @return 启用中的敏感词列表
     */
    List<AiSensitiveWord> enabledWords();

    /**
     * 分页查询敏感词。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSensitiveWordVo> queryPage(AiSensitiveWordBo bo, PageQuery pageQuery);

    /**
     * 查询敏感词详情。
     *
     * @param id 主键
     * @return 视图对象
     */
    AiSensitiveWordVo queryById(Long id);

    /**
     * 新增敏感词。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insert(AiSensitiveWordBo bo);

    /**
     * 修改敏感词。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean update(AiSensitiveWordBo bo);

    /**
     * 按主键集合删除敏感词（逻辑删除）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteByIds(Collection<Long> ids);

    /**
     * 失效词库缓存（增删改后调用）。
     */
    void refreshCache();

}