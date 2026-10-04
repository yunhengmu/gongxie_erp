package org.dromara.ai.service;

import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiEntryAgentBo;
import org.dromara.ai.domain.bo.AiExpertAgentBo;
import org.dromara.ai.domain.vo.AiAgentToolTrustVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiEntryAgentVo;
import org.dromara.ai.domain.vo.AiExpertAgentVo;
import org.dromara.ai.domain.vo.AiExpertVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * AI 智能体定义 Service 接口（REQ-AI-33：入口 / 专家物理拆表）。
 *
 * <p>拆表后本接口是**两表的统一解析门面**：装配与编排链路只依赖 {@link #getEnabledAgent} /
 * {@link #queryEntity} / {@link #queryEntities} 等解析方法，由实现按「先入口表、再专家表」解析，
 * 调用方无需关心智能体落在哪张表。</p>
 *
 * @author BizBuddy
 */
public interface IAiAgentService {

    // ---------------------------------------------------------------- 入口智能体（小Z）

    /**
     * 查询入口智能体（全平台唯一 1 行＝小Z）。
     *
     * @return 入口智能体视图对象；未配置时返回 null
     */
    AiEntryAgentVo queryEntryAgent();

    /**
     * 修改入口智能体（**只读写主表**）。
     *
     * <p>小Z 不绑定业务工具 / 知识库 / 技能 / 子智能体（能力由会话资源集提供），
     * 故入参忽略 id、按单例行更新、**不写任何绑定关系**；成功后清理装配缓存。</p>
     *
     * @param bo 业务对象（仅公共字段）
     * @return 是否修改成功
     */
    Boolean updateEntryAgent(AiEntryAgentBo bo);

    // ---------------------------------------------------------------- 专家智能体

    /**
     * 查询智能体详情（两表解析；入口返回入口 VO，专家返回专家 VO，含工具集与知识库绑定）。
     *
     * @param id 主键
     * @return 智能体视图对象；不存在时返回 null
     */
    AiAgentVo queryById(Long id);

    /**
     * 分页查询专家列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiExpertAgentVo> queryPageList(AiExpertAgentBo bo, PageQuery pageQuery);

    /**
     * 新增专家定义。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiExpertAgentBo bo);

    /**
     * 修改专家定义（成功后清理装配缓存，使新定义即时生效）。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiExpertAgentBo bo);

    /**
     * 按主键集合删除专家定义（存在会话时拒绝删除）。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    // ---------------------------------------------------------------- 选项与候选

    /**
     * 查询启用中的智能体选项（入口 + 全部启用专家，供管理面下拉，按可见范围过滤专家）。
     *
     * @return 智能体列表（含 {@code role}）
     */
    List<AiAgentVo> queryOptions();

    /**
     * 查询当前用户可见的**自动候选**专家清单（REQ-AI-30）。
     *
     * <p>＝启用 ∩ 按提问用户可见 ∩ {@code auto_route='1'}，与入口智能体编排时现算的清单同源。</p>
     *
     * @return 自动候选专家列表
     */
    List<AiExpertVo> queryExperts();

    /**
     * 查询当前用户可见的**全部**专家清单（**不过滤** {@code auto_route}）。
     *
     * <p>供对话工作台「指定专家」下拉：{@code auto_route='0'} 的专家只能被用户手动指定，
     * 故必须出现在该下拉里（详见 docs/18）。</p>
     *
     * @return 可见专家列表
     */
    List<AiExpertVo> queryVisibleExperts();

    // ---------------------------------------------------------------- 装配与解析

    /**
     * 获取启用中的智能体实体，供对话装配使用（两表解析）。
     *
     * @param id 智能体主键
     * @return 智能体实体
     */
    AiAgent getEnabledAgent(Long id);

    /**
     * 按主键获取智能体实体（不校验状态，两表解析）。
     *
     * @param id 智能体主键
     * @return 智能体实体，不存在时返回 null
     */
    AiAgent queryEntity(Long id);

    /**
     * 按主键集合批量获取智能体实体（两表合并，供各页展示智能体名）。
     *
     * @param ids 主键集合
     * @return 智能体实体列表（不存在的主键自动忽略）
     */
    List<AiAgent> queryEntities(Collection<Long> ids);

    /**
     * 按主键集合批量获取智能体视图对象（两表合并，供各页展示智能体名）。
     *
     * @param ids 主键集合
     * @return 智能体视图对象列表（不存在的主键自动忽略）
     */
    List<AiAgentVo> queryEntityVos(Collection<Long> ids);

    // ---------------------------------------------------------------- 工具信任决策

    /**
     * 记录「记住该工具」的信任决策（用户在确认卡片选择「记住该工具」时调用）。
     *
     * <p>写入「智能体 × 工具 × 用户」信任决策表（幂等：同三元组已存在则改写为「允许」）。
     * 信任**只对该用户生效**——同一智能体下其他用户仍会弹确认；**不改写**定义页的工具授权配置
     * （那是管理端的配置档位）。</p>
     *
     * @param agentId  智能体主键
     * @param userId   记住该工具的用户主键
     * @param toolCode 工具编码（业务工具编码或外部 MCP 工具名）
     * @return 是否写入成功
     */
    Boolean rememberToolForAgent(Long agentId, Long userId, String toolCode);

    /**
     * 查询该智能体已记住的工具（全部用户的信任决策清单，供定义页展示与撤销）。
     *
     * @param agentId 智能体主键
     * @return 信任决策清单（按工具编码、用户主键升序）
     */
    List<AiAgentToolTrustVo> queryTrustedTools(Long agentId);

    /**
     * 查询指定用户在指定智能体上已记住的工具编码集合（对话链路每轮按提问用户同步会话级规则）。
     *
     * @param agentId 智能体主键
     * @param userId  提问用户主键
     * @return 已记住且决策为「允许」的工具编码集合（无则空集）
     */
    Set<String> trustedToolCodes(Long agentId, Long userId);

    /**
     * 撤销某条信任决策（删除该「智能体 × 工具 × 用户」的信任行）。
     *
     * <p>撤销在提问用户**下一次提问**时生效（对话链路每轮按当前用户同步会话级权限上下文）。</p>
     *
     * @param agentId  智能体主键
     * @param userId   记住该工具的用户主键
     * @param toolCode 工具编码
     * @return 是否撤销成功（原本不存在时返回 false）
     */
    Boolean revokeTrustedTool(Long agentId, Long userId, String toolCode);

}
