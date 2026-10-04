package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiUsageStatBo;
import org.dromara.ai.domain.dto.ChatTokenUsage;
import org.dromara.ai.domain.vo.AiUsageOverviewVo;
import org.dromara.ai.domain.vo.AiUsageStatExportVo;
import org.dromara.ai.domain.vo.AiUsageStatVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * AI 用量统计 Service 接口（REQ-AI-07）
 *
 * @author BizBuddy
 */
public interface IAiUsageStatService {

    /**
     * 累加一轮对话的用量（按日期 + 智能体 + 模型 + 用户）。
     *
     * @param userId        触发用户
     * @param agentId       智能体主键
     * @param modelId       模型主键
     * @param tokens        本次 Token 用量
     * @param toolCallCount 本次工具调用次数
     * @param requestCount  本次请求数（HITL 恢复段传 0，避免一次提问被计为两次）
     */
    void accumulate(Long userId, Long agentId, Long modelId, ChatTokenUsage tokens, int toolCallCount,
                    int requestCount);

    /**
     * 分页查询用量明细。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiUsageStatVo> queryPage(AiUsageStatBo bo, PageQuery pageQuery);

    /**
     * 用量总览（合计 + 按日期 / 智能体 / 模型分组）。
     *
     * @param bo 查询条件
     * @return 总览
     */
    AiUsageOverviewVo overview(AiUsageStatBo bo);

    /**
     * 构建导出数据。
     *
     * @param bo 查询条件
     * @return 导出数据
     */
    List<AiUsageStatExportVo> buildExportData(AiUsageStatBo bo);

    /**
     * 汇总某用户某日的总 Token 用量（配额校验用；Redis 计数缺失时回退数据库）。
     *
     * @param userId 用户主键
     * @return 当日总 Token
     */
    long todayTokens(Long userId);

    /**
     * 汇总某用户某日的请求数（配额校验用；Redis 计数缺失时回退数据库）。
     *
     * @param userId 用户主键
     * @return 当日请求数
     */
    long todayRequests(Long userId);

    /**
     * 汇总某用户某月的总 Token 用量（配额校验用）。
     *
     * @param userId 用户主键
     * @return 当月总 Token
     */
    long monthTokens(Long userId);

}