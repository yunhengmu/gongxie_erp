package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiPermissionDecisionBo;
import org.dromara.ai.domain.vo.AiPermissionDecisionVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * AI 工具权限决策日志 Service 接口（REQ-AI-27）
 *
 * @author BizBuddy
 */
public interface IAiPermissionDecisionService {

    /**
     * 记录一条「需人工确认」的决策（首段流暂停时）。
     *
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     * @param agentId   智能体主键
     * @param userId    触发用户
     * @param toolCode  工具编码
     * @param toolName  工具名称
     * @param toolInput 工具入参 JSON
     */
    void recordAsk(Long sessionId, Long messageId, Long agentId, Long userId,
                   String toolCode, String toolName, String toolInput);

    /**
     * 记录一条「实际执行」的决策（工具未被配置为需确认时）。
     *
     * @param sessionId  会话主键
     * @param messageId  助手消息主键
     * @param agentId    智能体主键
     * @param userId     触发用户
     * @param toolCode   工具编码
     * @param toolName   工具名称
     * @param toolInput  工具入参 JSON
     * @param durationMs 工具执行耗时（毫秒）
     */
    void recordAllow(Long sessionId, Long messageId, Long agentId, Long userId,
                     String toolCode, String toolName, String toolInput, Long durationMs);

    /**
     * 回填人工确认结果（确认 / 拒绝）。
     *
     * <p>对话页对一次暂停整体确认或拒绝，故按助手消息维度回填该轮全部 ASK 决策。</p>
     *
     * @param messageId 助手消息主键
     * @param confirmed 是否确认执行
     * @param remember  是否记住该工具
     */
    void applyConfirm(Long messageId, boolean confirmed, boolean remember);

    /**
     * 分页查询决策日志。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiPermissionDecisionVo> queryPage(AiPermissionDecisionBo bo, PageQuery pageQuery);

}