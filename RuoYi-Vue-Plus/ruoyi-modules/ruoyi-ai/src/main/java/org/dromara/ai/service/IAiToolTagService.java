package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiToolTagBo;
import org.dromara.ai.domain.vo.AiToolTagVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 业务工具标签 Service 接口
 *
 * <p>标签用于给业务工具做分类与检索：一个工具可打多个标签；「按标签查工具」为
 * **任一命中（OR）** 语义，另支持「无标签」筛选。标签仅用于管理面分类，
 * **不参与智能体装配**。</p>
 *
 * @author BizBuddy
 */
public interface IAiToolTagService {

    /**
     * 分页查询标签（含使用该标签的工具数）。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiToolTagVo> queryPageList(AiToolTagBo bo, PageQuery pageQuery);

    /**
     * 查询全部标签（含使用计数），供工具列表筛选与工具表单多选使用。
     *
     * @return 标签列表（按名称升序）
     */
    List<AiToolTagVo> queryOptions();

    /**
     * 新增标签（名称唯一）。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiToolTagBo bo);

    /**
     * 修改标签（名称唯一）。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiToolTagBo bo);

    /**
     * 按主键集合删除标签，并**级联解除**其与全部工具的关联。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 查询某工具已打的标签主键集合。
     *
     * @param toolId 工具主键
     * @return 标签主键集合
     */
    List<Long> tagIdsOfTool(Long toolId);

    /**
     * 批量查询多个工具的标签（供工具列表填充标签列）。
     *
     * @param toolIds 工具主键集合
     * @return 工具主键 → 标签列表（无标签的工具不出现在 Map 中）
     */
    Map<Long, List<AiToolTagVo>> tagMapByToolIds(Collection<Long> toolIds);

    /**
     * 按标签集合反查工具主键（**任一命中（OR）** 语义）。
     *
     * @param tagIds 标签主键集合
     * @return 命中任一标签的工具主键集合（去重）
     */
    List<Long> toolIdsByTagIds(Collection<Long> tagIds);

    /**
     * 查询全部「已打标签」的工具主键（供「无标签」筛选做反向排除）。
     *
     * @return 已打标签的工具主键集合（去重）
     */
    List<Long> allTaggedToolIds();

    /**
     * 覆盖式设置某工具的标签（先删后插，保存工具时调用）。
     *
     * @param toolId 工具主键
     * @param tagIds 标签主键集合（空集合表示清除全部标签）
     */
    void applyToolTags(Long toolId, Collection<Long> tagIds);

    /**
     * **批量打标签（追加语义）**：为勾选的若干工具追加若干标签。
     *
     * <p>与 {@link #applyToolTags} 的「覆盖式设置」不同：本方法**不动工具已有的其他标签**，
     * 已存在的关联跳过（幂等），即「批量打标签」的直觉语义。</p>
     *
     * @param toolIds 目标工具主键集合
     * @param tagIds  标签主键集合
     * @return 实际新增的关联数
     */
    int applyTagsToTools(Collection<Long> toolIds, Collection<Long> tagIds);

    /**
     * **批量移除标签**：从勾选的若干工具上移除指定标签。
     *
     * <p>只删除「工具 × 标签」的指定关联；未打该标签的工具不受影响，工具的其他标签保留。</p>
     *
     * @param toolIds 目标工具主键集合
     * @param tagIds  标签主键集合
     * @return 实际移除的关联数
     */
    int removeTagsFromTools(Collection<Long> toolIds, Collection<Long> tagIds);

    /**
     * 清理若干工具的标签关联（删除工具时调用）。
     *
     * @param toolIds 工具主键集合
     */
    void removeByToolIds(Collection<Long> toolIds);

}