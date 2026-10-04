package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiTraceBo;
import org.dromara.ai.domain.dto.AgentSnapshot;
import org.dromara.ai.domain.vo.AiTraceVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * AI 链路追踪 Service 接口（REQ-AI-28）
 *
 * <p>写入侧由执行链路（实时对话 / HITL 恢复 / 定时任务）调用：开始时落一条 running 链路并
 * 记录**执行当时的装配快照**（模型版本 + 上下文清单），执行中追加状态迁移，收尾时累加
 * 状态、耗时与用量。读取侧提供管理页的分页查询与详情。</p>
 *
 * @author BizBuddy
 */
public interface IAiTraceService {

    /**
     * 分页查询链路列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiTraceVo> queryPageList(AiTraceBo bo, PageQuery pageQuery);

    /**
     * 查询链路详情（含解析后的上下文清单与状态迁移时间线）。
     *
     * @param id 主键
     * @return 链路视图对象
     */
    AiTraceVo queryById(Long id);

    /**
     * 开始一条链路并写入执行期快照。
     *
     * @param sessionId 会话主键
     * @param agentId   智能体主键
     * @param userId    触发用户
     * @param source    来源（chat / task）
     * @param snapshot  装配快照（模型版本 + 上下文清单）
     * @return 链路主键
     */
    default Long start(Long sessionId, Long agentId, Long userId, String source, AgentSnapshot snapshot) {
        return start(sessionId, agentId, userId, source, snapshot, null);
    }

    /**
     * 开始一条链路并写入执行期快照，可指定父链路（REQ-AI-30 编排链路的专家子链路）。
     *
     * @param sessionId     会话主键
     * @param agentId       智能体主键
     * @param userId        触发用户
     * @param source        来源（chat / task）
     * @param snapshot      装配快照（模型版本 + 上下文清单）
     * @param parentTraceId 父链路主键（编排链路为父、专家调用为子；顶层传 null）
     * @return 链路主键
     */
    Long start(Long sessionId, Long agentId, Long userId, String source, AgentSnapshot snapshot, Long parentTraceId);

    /**
     * 查找待确认消息对应的既有链路（HITL 恢复段复用同一条，不新建）。
     *
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     * @return 链路主键；不存在时返回 null
     */
    Long findIdByMessage(Long sessionId, Long messageId);

    /**
     * 追加一条状态迁移（超过上限时停止追加，避免异常链路把明细撑爆）。
     *
     * @param traceId 链路主键
     * @param state   状态节点（如 start / tool_call / pending_confirm / resume / done / error）
     * @param detail  明细（工具名、错误摘要等，可为空）
     */
    void appendTransition(Long traceId, String state, String detail);

    /**
     * 回填助手消息主键（收尾或暂停时才产生，恢复段据此复用同一条链路）。
     *
     * @param traceId   链路主键
     * @param messageId 助手消息主键
     */
    void attachMessage(Long traceId, Long messageId);

    /**
     * 累加收尾：更新状态与失败原因，耗时 / 工具次数 / Token 按段累加（恢复段不覆盖首段数据）。
     *
     * @param traceId         链路主键
     * @param status          链路状态（success / pending_confirm / failed / aborted）
     * @param durationMs      本段耗时（为空不更新）
     * @param toolCallCount   本段工具调用次数（为空不更新）
     * @param promptTokens    本段输入 Token（为空不更新）
     * @param completionTokens 本段输出 Token（为空不更新）
     * @param totalTokens     本段合计 Token（为空不更新）
     * @param errorMsg        失败原因（为空不更新）
     */
    void accumulate(Long traceId, String status, Long durationMs, Integer toolCallCount,
                    Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorMsg);

}