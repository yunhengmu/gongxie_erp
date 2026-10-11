package org.dromara.ai.service;

import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiToolCallLog;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiSessionExportVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiSourceVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * AI 会话 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiSessionService {

    /**
     * 创建会话（会话主键即 AgentScope 的 sessionId）。
     *
     * <p>场景包在创建时选定并**固化到会话**（{@code scenario_pack_id} + 编码快照），会话内不可更换：
     * 权限上下文按 {@code (userId, sessionId)} 槽位生效，同一会话内改授权不会重新判定，需新建会话。</p>
     *
     * @param agentId        智能体主键
     * @param scenarioPackId 所选场景包主键（可空：为空表示不选包，该会话不接入任何 MCP 工具）
     * @param title          会话标题，留空时自动生成
     * @param pinnedExpertId 指定专家主键（可空；REQ-AI-31：非空表示本会话强制路由到该专家）
     * @return 会话视图对象（含生成的会话主键）
     */
    AiSessionVo createSession(Long agentId, Long scenarioPackId, String title, Long pinnedExpertId);

    /**
     * 指定 / 取消指定专家（REQ-AI-31，仅本人会话）。
     *
     * <p>指定时校验：专家存在 ∧ 启用 ∧ {@code agent_role='2'} ∧ 提问用户可见；取消时传 {@code null}。
     * 与场景包不同，本项**会话内可变**——会话发言人恒为入口智能体、上下文不因指定 / 取消而中断。</p>
     *
     * @param sessionId     会话主键
     * @param expertAgentId 专家主键；{@code null} 表示取消指定
     * @return 是否更新成功
     */
    Boolean setPinnedExpert(Long sessionId, Long expertAgentId);

    /**
     * 查询本人会话详情（REQ-AI-31：会话头按 {@code pinnedExpertId} 展示「已指定：X」）。
     *
     * @param sessionId 会话主键
     * @return 会话视图对象
     */
    AiSessionVo getOwnedSessionVo(Long sessionId);

    /**
     * 创建无人值守会话（定时任务链路，无登录上下文）。
     *
     * <p>归属用户与部门由调用方显式指定（即任务的运行身份用户），不使用登录态。</p>
     *
     * @param agentId  智能体主键
     * @param title    会话标题
     * @param userId   运行身份用户
     * @param deptId   运行身份部门
     * @param source   会话来源（{@link org.dromara.ai.domain.AiSession#SOURCE_TASK}）
     * @return 会话实体（含生成的主键）
     */
    AiSession createUnattendedSession(Long agentId, String title, Long userId, Long deptId, String source);

    /**
     * 落库用户消息（无人值守链路，不做会话归属校验）。
     *
     * @param sessionId 会话主键
     * @param content   用户输入内容
     * @return 用户消息实体
     */
    AiMessage appendUserMessageUnattended(Long sessionId, String content);

    /**
     * 查询会话实体（不校验归属；供无人值守链路续接同一会话使用）。
     *
     * @param sessionId 会话主键
     * @return 会话实体（不存在时抛业务异常）
     */
    AiSession getSession(Long sessionId);

    /**
     * 分页查询当前用户的会话列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSessionVo> queryMySessions(AiSessionBo bo, PageQuery pageQuery);

    /**
     * 查询会话消息列表（校验会话归属）。
     *
     * @param sessionId 会话主键
     * @return 消息列表（按主键升序）
     */
    List<AiMessageVo> queryMessages(Long sessionId);

    /**
     * 删除一问一答（整轮）：该用户消息本身，以及紧随其后的助手消息（直到下一条用户消息为止）。
     *
     * <p><b>只删平台侧记录，不动模型记忆</b>：AgentScope 的会话状态按 {@code (userId, sessionId)} 整体存放，
     * 无法只摘掉某一轮，因此本轮内容仍会留在模型上下文中（详情见实现注释）。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 用户消息主键
     * @return 是否删除成功
     */
    Boolean deleteChatRound(Long sessionId, Long messageId);

    /**
     * 落库用户消息，并刷新会话活跃时间与消息条数（标题仍为默认时按首问改写）。
     *
     * @param sessionId 会话主键
     * @param content   用户输入内容
     * @return 用户消息实体
     */
    AiMessage appendUserMessage(Long sessionId, String content);

    /**
     * 落库用户消息（可携带附件），并刷新会话活跃时间与消息条数（标题仍为默认时按首问改写）。
     *
     * <p>内容与附件**至少提供一个**：两者皆空时抛业务异常；附件须为本人本会话上传且未发送的暂存件。</p>
     *
     * @param sessionId     会话主键
     * @param content       用户输入内容（可为空，此时须有附件）
     * @param attachmentIds 随本消息发送的附件主键集合（可空）
     * @return 用户消息实体
     */
    AiMessage appendUserMessage(Long sessionId, String content, List<Long> attachmentIds);

    /**
     * 落库助手回答，并批量落库工具调用明细（同一事务内）。
     *
     * @param sessionId  会话主键
     * @param content    回答正文
     * @param replyId    AgentScope replyId
     * @param durationMs 本轮耗时（毫秒）
     * @param toolLogs   工具调用明细（可为空）
     * @param tokens     本轮 Token 用量（可为空）
     * @param userId     触发用户（工具明细的归属用户；无人值守链路为运行身份用户）
     * @param sources    回答参考来源（知识库命中；可空）
     * @return 助手消息实体
     */
    AiMessage persistAssistantTurn(Long sessionId, String content, String replyId, Long durationMs,
                                   List<AiToolCallLog> toolLogs, ChatTokenUsage tokens, Long userId,
                                   List<AiSourceVo> sources);

    /**
     * 落库助手失败消息。
     *
     * @param sessionId  会话主键
     * @param errorMsg   失败原因
     * @param durationMs 本轮耗时（毫秒）
     * @return 助手消息实体
     */
    AiMessage persistAssistantFailure(Long sessionId, String errorMsg, Long durationMs);

    /**
     * 落库「已停止生成」的助手消息（用户主动停止本轮执行），状态为已中止。
     *
     * @param sessionId  会话主键
     * @param content    停止前已产生的回答正文（可为空）
     * @param durationMs 本轮耗时（毫秒）
     * @param sources    回答参考来源（知识库命中；可空）
     * @return 助手消息实体
     */
    AiMessage persistAssistantAborted(Long sessionId, String content, Long durationMs,
                                      List<AiSourceVo> sources);

    /**
     * 落库「等待人工确认」的助手消息（HITL 暂停），状态为待确认。
     *
     * @param sessionId  会话主键
     * @param content    暂停前已产生的回答正文（可为空）
     * @param replyId    AgentScope replyId
     * @param durationMs 暂停前耗时（毫秒）
     * @param toolLogs   待确认工具调用明细（可为空）
     * @param tokens     暂停前 Token 用量（可为空）
     * @param userId     触发用户（工具明细的归属用户）
     * @param sources    回答参考来源（知识库命中；可空）
     * @return 助手消息实体
     */
    AiMessage persistPendingTurn(Long sessionId, String content, String replyId, Long durationMs,
                                 List<AiToolCallLog> toolLogs, ChatTokenUsage tokens, Long userId,
                                 List<AiSourceVo> sources);

    /**
     * 追加更新同一条助手消息（HITL 确认后恢复生成时使用，避免一次问答拆成两条消息）。
     *
     * <p>正文追加、耗时与 Token 累加、工具调用次数累加，并覆盖消息状态。</p>
     *
     * @param messageId          助手消息主键
     * @param appendContent      追加的正文
     * @param status             覆盖后的消息状态
     * @param additionalDurationMs 追加耗时（毫秒）
     * @param toolLogs           追加的工具调用明细（可为空）
     * @param tokens             追加的 Token 用量（可为空）
     * @param sources            回答参考来源（知识库命中；可空：为空时保留原值）
     * @return 更新后的助手消息实体
     */
    AiMessage appendAssistantTurn(Long messageId, String appendContent, String status, Long additionalDurationMs,
                                  List<AiToolCallLog> toolLogs, ChatTokenUsage tokens, List<AiSourceVo> sources);

    /**
     * 查询会话内仍处于「等待确认」状态的助手消息（供刷新页面后恢复确认卡片）。
     *
     * @param sessionId 会话主键
     * @return 待确认的助手消息列表（按主键升序）
     */
    List<AiMessageVo> queryPendingMessages(Long sessionId);

    /**
     * 刷新会话活跃时间并累加消息条数。
     *
     * @param sessionId 会话主键
     */
    void touchSession(Long sessionId);

    /**
     * 终止会话（状态置为已结束）。
     *
     * @param sessionId 会话主键
     * @return 是否终止成功
     */
    Boolean closeSession(Long sessionId);

    /**
     * 重命名会话（仅限本人会话）。
     *
     * @param sessionId 会话主键
     * @param title     新标题
     * @return 是否重命名成功
     */
    Boolean renameSession(Long sessionId, String title);

    /**
     * 置顶 / 取消置顶会话（仅限本人会话）。
     *
     * @param sessionId 会话主键
     * @param top       是否置顶
     * @return 是否操作成功
     */
    Boolean toggleTop(Long sessionId, Boolean top);

    /**
     * 获取归属当前用户的会话（不存在或非本人 → 业务异常）。
     *
     * @param sessionId 会话主键
     * @return 会话实体
     */
    AiSession getOwnedSession(Long sessionId);

    /**
     * 分页查询会话列表（会话管理页，不限定归属用户）。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSessionVo> queryPageList(AiSessionBo bo, PageQuery pageQuery);

    /**
     * 查询会话消息列表（会话管理页，不限定归属用户）。
     *
     * @param sessionId 会话主键
     * @return 消息列表（按主键升序）
     */
    List<AiMessageVo> querySessionMessages(Long sessionId);

    /**
     * 查询会话的工具调用明细。
     *
     * @param sessionId 会话主键
     * @return 工具调用明细（按主键升序）
     */
    List<AiToolCallLogVo> queryToolCalls(Long sessionId);

    /**
     * 查询归属当前用户的会话的工具调用明细（供对话工作台还原历史工具卡片）。
     *
     * @param sessionId 会话主键
     * @return 工具调用明细（按主键升序）
     */
    List<AiToolCallLogVo> queryOwnedToolCalls(Long sessionId);

    /**
     * 终止会话（会话管理页，不限定归属用户）。
     *
     * @param ids 会话主键集合
     * @return 是否终止成功
     */
    Boolean closeWithValidByIds(Collection<Long> ids);

    /**
     * 删除会话（逻辑删除会话与其消息，并清理该会话的模型记忆）。
     *
     * @param ids 会话主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 构建会话导出数据。
     *
     * @param bo 查询条件（与列表页一致）
     * @return 导出数据
     */
    List<AiSessionExportVo> buildExportData(AiSessionBo bo);

    /**
     * 查询会话涉及的全部智能体选项（供会话管理页按智能体筛选）。
     *
     * @return 智能体列表（含停用，仅含会话中出现过的）
     */
    List<AiAgentVo> queryAgentOptions();

}
