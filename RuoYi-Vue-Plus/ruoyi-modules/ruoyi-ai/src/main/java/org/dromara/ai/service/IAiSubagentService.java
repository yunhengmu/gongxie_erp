package org.dromara.ai.service;

import org.dromara.ai.domain.AiSubagent;
import org.dromara.ai.domain.bo.AiSubagentBo;
import org.dromara.ai.domain.vo.AiSubagentOptionVo;
import org.dromara.ai.domain.vo.AiSubagentVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * AI 子智能体 Service 接口（REQ-AI-04）
 *
 * <p>子智能体是平台级可复用定义，与智能体为多对多绑定（{@code ai_agent_subagent}）：
 * 装配时只把被绑定的子智能体交给 AgentScope（作为 {@code SubagentDeclaration}），
 * 主智能体经框架内置的 {@code agent_spawn} 工具委派任务。</p>
 *
 * <p>工具白名单**只允许只读工具**：框架不为子智能体继承父级的 allow / ask 规则（仅继承 deny），
 * 子智能体内没有人工确认闸门，故写入类工具一律不得下放（见 docs/00 第 42 项）。</p>
 *
 * @author BizBuddy
 */
public interface IAiSubagentService {

    /**
     * 分页查询子智能体列表（管理页全量）。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSubagentVo> queryPageList(AiSubagentBo bo, PageQuery pageQuery);

    /**
     * 查询子智能体详情。
     *
     * @param id 主键
     * @return 子智能体视图对象
     */
    AiSubagentVo queryById(Long id);

    /**
     * 新增子智能体。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiSubagentBo bo);

    /**
     * 修改子智能体（子智能体标识不可修改）。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiSubagentBo bo);

    /**
     * 按主键集合物理删除子智能体（被智能体绑定时拒绝删除）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 查询启用中的子智能体选项（供智能体定义页绑定子智能体）。
     *
     * @return 子智能体选项列表
     */
    List<AiSubagentOptionVo> queryOptions();

    /**
     * 获取启用中的子智能体实体（供绑定校验与装配使用）。
     *
     * @param id 子智能体主键
     * @return 子智能体实体
     */
    AiSubagent getEnabledSubagent(Long id);

    /**
     * 查询智能体绑定的启用中子智能体（保持绑定顺序），供装配层生成声明使用。
     *
     * @param agentId 智能体主键
     * @return 子智能体实体列表（可能为空）
     */
    List<AiSubagent> queryBoundEnabledSubagents(Long agentId);

    /**
     * 查询智能体绑定的子智能体主键集合（保持绑定顺序）。
     *
     * @param agentId 智能体主键
     * @return 子智能体主键集合
     */
    List<Long> queryBoundSubagentIds(Long agentId);

    /**
     * 批量查询「智能体 → 绑定子智能体主键」映射。
     *
     * @param agentIds 智能体主键集合
     * @return 映射（无绑定的智能体不出现在 Map 中）
     */
    Map<Long, List<Long>> queryAgentSubagentMap(Collection<Long> agentIds);

    /**
     * 覆盖式保存智能体的子智能体绑定（先删后插）。
     *
     * @param agentId     智能体主键
     * @param subagentIds 子智能体主键集合
     */
    void replaceAgentBindings(Long agentId, Collection<Long> subagentIds);

    /**
     * 清理若干智能体的子智能体绑定（删除智能体时调用）。
     *
     * @param agentIds 智能体主键集合
     */
    void deleteAgentBindings(Collection<Long> agentIds);

}