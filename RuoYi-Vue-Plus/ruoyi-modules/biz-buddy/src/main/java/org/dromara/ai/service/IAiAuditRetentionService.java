package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiAuditRetentionBo;
import org.dromara.ai.domain.vo.AiAuditRetentionVo;

/**
 * 审计与运行数据保留策略 Service。
 *
 * <p>补上审计明细「无保留策略、线性增长」的缺口：保留天数由运行期配置（Redis
 * {@code bizbuddy:ai:runtime:audit-retention}）决定，**默认 0＝不限、不清理**（行为与改造前一致）；
 * 清理为**人工触发**（「编排运行」页的「清理过期数据」按钮），不引入自动删除。</p>
 *
 * <p>清理范围为运行 / 审计明细 6 表：{@code ai_trace}、{@code ai_tool_call_log}、
 * {@code ai_permission_decision_log}、{@code ai_agent_task_run}、{@code ai_route_run}、
 * {@code ai_route_expert_call}；**不含会话正文 {@code ai_message}**。</p>
 *
 * @author BizBuddy
 */
public interface IAiAuditRetentionService {

    /**
     * 查询当前保留策略与各表超期行数（超期行数供页面在清理前展示体量）。
     *
     * @return 保留策略视图
     */
    AiAuditRetentionVo getRetention();

    /**
     * 保存保留策略（写入运行期配置，下一次清理即按新天数执行）。
     *
     * @param bo 保留策略
     */
    void saveRetention(AiAuditRetentionBo bo);

    /**
     * 按当前保留策略清理超期数据（物理删除），返回逐表实际删除行数。
     *
     * @return 清理结果视图
     */
    AiAuditRetentionVo purge();

}
