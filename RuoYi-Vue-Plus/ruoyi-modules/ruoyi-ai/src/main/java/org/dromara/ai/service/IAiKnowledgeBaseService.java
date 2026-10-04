package org.dromara.ai.service;

import org.dromara.ai.domain.AiKnowledgeBase;
import org.dromara.ai.domain.bo.AiKnowledgeBaseBo;
import org.dromara.ai.domain.vo.AiKnowledgeBaseVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * AI 知识库 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiKnowledgeBaseService {

    /**
     * 查询知识库详情。
     *
     * @param id 主键
     * @return 知识库视图对象
     */
    AiKnowledgeBaseVo queryById(Long id);

    /**
     * 分页查询知识库列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiKnowledgeBaseVo> queryPageList(AiKnowledgeBaseBo bo, PageQuery pageQuery);

    /**
     * 查询启用中的知识库选项（供文档、检索页面下拉选择）。
     *
     * @return 知识库列表
     */
    List<AiKnowledgeBaseVo> queryOptions();

    /**
     * 查询指定用户「可见且启用」的知识库选项（供智能体定义页绑定选择器使用）。
     *
     * <p>可见口径与 {@code ai_agent.visible_scope} 一致：公开 / 本部门（owner_dept_id） / 仅本人（create_by）。</p>
     *
     * @param userId 用户主键
     * @return 可见知识库列表
     */
    List<AiKnowledgeBaseVo> queryVisibleOptions(Long userId);

    /**
     * 求「指定知识库集合」与「指定用户可见集合」的交集。
     *
     * <p>供对话检索链路在**运行时**过滤使用——Agent 实例按智能体缓存（非按用户），
     * 故可见性只能在每次调用时按提问用户判定。</p>
     *
     * @param kbIds  智能体绑定的知识库主键集合
     * @param userId 提问用户主键
     * @return 该用户实际可用的知识库主键集合（保持入参顺序）
     */
    List<Long> filterVisibleKbIds(Collection<Long> kbIds, Long userId);

    /**
     * 校验知识库集合对指定用户是否全部可见，不可见时抛出业务异常。
     *
     * @param kbIds  待校验的知识库主键集合
     * @param userId 用户主键
     * @param scene  场景描述（用于错误提示，如「绑定」「检索」）
     */
    void validateVisible(Collection<Long> kbIds, Long userId, String scene);

    /**
     * 校验单个知识库对指定用户是否可见，不可见时抛出业务异常。
     *
     * @param kbId   知识库主键
     * @param userId 用户主键
     * @param scene  场景描述
     */
    void validateVisible(Long kbId, Long userId, String scene);

    /**
     * 查询「公开且启用」的知识库主键集合（供 MCP 出口使用）。
     *
     * <p>MCP 出口的调用方只携带平台口令、没有平台用户，故不按用户过滤；为避免越权，
     * 出口只覆盖 {@code visible_scope=0}（公开）且启用中的知识库（REQ-AI-15 口径决策）。</p>
     *
     * @return 公开且启用中的知识库主键集合（按创建顺序）
     */
    List<Long> listPublicEnabledKbIds();

    /**
     * 新增知识库。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiKnowledgeBaseBo bo);

    /**
     * 修改知识库。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiKnowledgeBaseBo bo);

    /**
     * 按主键集合删除知识库（存在文档时拒绝删除）。
     *
     * @param ids     主键集合
     * @param isValid 是否执行删除前校验
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

    /**
     * 获取启用中的知识库实体，供文档与检索链路使用。
     *
     * @param id 知识库主键
     * @return 知识库实体
     */
    AiKnowledgeBase getEnabledKnowledgeBase(Long id);

    /**
     * 按主键获取知识库实体（不校验状态，供删除清理等场景使用）。
     *
     * @param id 知识库主键
     * @return 知识库实体，不存在时返回 null
     */
    AiKnowledgeBase queryEntity(Long id);

}