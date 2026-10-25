package org.dromara.ai.service;

import org.dromara.ai.domain.AiSkill;
import org.dromara.ai.domain.bo.AiSkillBo;
import org.dromara.ai.domain.vo.AiSkillOptionVo;
import org.dromara.ai.domain.vo.AiSkillVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * AI 技能 Service 接口
 *
 * <p>技能正文以 SKILL.md 落库（{@code ai_skill.skill_content}），参考资料落 {@code ai_skill_resource}。
 * 与智能体为多对多绑定（{@code ai_agent_skill}）：装配时只放行被绑定的技能，
 * 运行期再按提问用户的生效范围过滤。</p>
 *
 * @author BizBuddy
 */
public interface IAiSkillService {

    /**
     * 分页查询技能列表（管理页，全量）。
     *
     * <p>附带使用计数（聚合工具调用明细中的技能加载调用）、最近使用时间与陈旧归档状态。</p>
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSkillVo> queryPageList(AiSkillBo bo, PageQuery pageQuery);

    /**
     * 查询技能详情（含参考资料明细与使用计数）。
     *
     * @param id 主键
     * @return 技能视图对象
     */
    AiSkillVo queryById(Long id);

    /**
     * 新增技能。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiSkillBo bo);

    /**
     * 修改技能（技能标识不可修改）。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiSkillBo bo);

    /**
     * 按主键集合物理删除技能（参考资料随外键级联清理，被智能体绑定时拒绝删除）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 查询「指定用户可见」的启用中技能选项（供智能体定义页绑定技能）。
     *
     * @param userId 用户主键
     * @return 技能选项列表
     */
    List<AiSkillOptionVo> queryVisibleOptions(Long userId);

    /**
     * 求「指定技能集合」与「指定用户可见集合」的交集（保持入参顺序）。
     *
     * @param skillIds 技能主键集合
     * @param userId   用户主键
     * @return 该用户实际可见的技能主键集合
     */
    List<Long> filterVisibleSkillIds(Collection<Long> skillIds, Long userId);

    /**
     * 校验技能集合对指定用户是否全部可见，不可见时抛出业务异常。
     *
     * @param skillIds 技能主键集合
     * @param userId   用户主键
     * @param scene    使用场景（用于异常文案，如「绑定」）
     */
    void validateVisible(Collection<Long> skillIds, Long userId, String scene);

    /**
     * 获取启用中的技能实体，供装配与绑定校验使用。
     *
     * @param id 技能主键
     * @return 技能实体
     */
    AiSkill getEnabledSkill(Long id);

    /**
     * 解析运行期真正生效的技能过滤键：智能体绑定 ∩ 启用中 ∩ 提问用户可见。
     *
     * <p>供对话与定时任务链路构造 AgentScope 的 {@code SkillFilter} 使用。
     * 每个技能返回**两个键**：技能标识（name）与 AgentScope 的 skillId
     * （{@code AgentSkill#getSkillId()} = {@code name + "_" + source}）——
     * 框架按 {@code RegisteredSkill#getSkillId()} 做过滤、按 {@code AgentSkill#getSkillId()}
     * 渲染 prompt 与加载，两处口径不完全一致，白名单同时给出两种形式即可稳定命中。</p>
     *
     * @param agentId 智能体主键
     * @param userId  提问用户主键
     * @return 生效技能过滤键集合（可能为空）
     */
    List<String> resolveRuntimeSkillFilterKeys(Long agentId, Long userId);

    /**
     * 解析运行期技能过滤键（含场景包附加技能）：{@code 智能体绑定 ∪ 场景包技能}
     * ∩ 启用中 ∩ 提问用户可见。
     *
     * @param agentId       智能体主键
     * @param extraSkillIds 场景包附加技能主键（可为空）
     * @param userId        提问用户主键
     * @return 生效技能过滤键集合（可能为空）
     */
    List<String> resolveRuntimeSkillFilterKeys(Long agentId, Collection<Long> extraSkillIds, Long userId);

    /**
     * 解析智能体装配用的技能过滤键：绑定 ∩ 启用中。
     *
     * <p>装配按智能体缓存，故此处不做提问用户过滤（可见范围与用户相关）；
     * 用户维度的过滤在运行期由 {@link #resolveRuntimeSkillFilterKeys(Long, Long)} 完成。键形式同上。</p>
     *
     * @param agentId 智能体主键
     * @return 技能过滤键集合（可能为空）
     */
    List<String> resolveAgentSkillFilterKeys(Long agentId);

    /**
     * 解析智能体装配用的技能过滤键（含场景包附加技能）：{@code 智能体绑定 ∪ 场景包技能}
     * ∩ 启用中。
     *
     * @param agentId       智能体主键
     * @param extraSkillIds 场景包附加技能主键（可为空）
     * @return 技能过滤键集合（可能为空）
     */
    List<String> resolveAgentSkillFilterKeys(Long agentId, Collection<Long> extraSkillIds);

    /**
     * 查询智能体绑定的技能主键集合（保持绑定顺序）。
     *
     * @param agentId 智能体主键
     * @return 技能主键集合
     */
    List<Long> queryBoundSkillIds(Long agentId);

    /**
     * 批量查询「智能体 → 绑定技能主键」映射。
     *
     * @param agentIds 智能体主键集合
     * @return 映射（无绑定的智能体不出现在 Map 中）
     */
    Map<Long, List<Long>> queryAgentSkillMap(Collection<Long> agentIds);

    /**
     * 覆盖式保存智能体的技能绑定（先删后插）。
     *
     * @param agentId  智能体主键
     * @param skillIds 技能主键集合
     */
    void replaceAgentBindings(Long agentId, Collection<Long> skillIds);

    /**
     * 清理若干智能体的技能绑定（删除智能体时调用）。
     *
     * @param agentIds 智能体主键集合
     */
    void deleteAgentBindings(Collection<Long> agentIds);

}
