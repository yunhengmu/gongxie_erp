package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiMessageAuditBo;
import org.dromara.ai.domain.bo.AiToolCallAuditBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageAuditExportVo;
import org.dromara.ai.domain.vo.AiMessageAuditVo;
import org.dromara.ai.domain.vo.AiToolCallAuditVo;
import org.dromara.ai.domain.vo.AiToolCallStatVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * AI 会话与工具调用审计 Service 接口
 *
 * @author BizBuddy
 */
public interface IAiAuditService {

    /**
     * 分页查询会话消息（跨会话检索）。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiMessageAuditVo> queryMessagePage(AiMessageAuditBo bo, PageQuery pageQuery);

    /**
     * 构建消息导出数据（按当前筛选条件）。
     *
     * @param bo 查询条件
     * @return 导出数据
     */
    List<AiMessageAuditExportVo> buildMessageExport(AiMessageAuditBo bo);

    /**
     * 分页查询工具调用明细。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiToolCallAuditVo> queryToolCallPage(AiToolCallAuditBo bo, PageQuery pageQuery);

    /**
     * 按工具统计调用次数与失败率（沿用当前筛选条件）。
     *
     * @param bo 查询条件
     * @return 统计结果
     */
    List<AiToolCallStatVo> queryToolCallStats(AiToolCallAuditBo bo);

    /**
     * 查询「运行数据中出现过的智能体」选项（工具调用明细的归属智能体 ∪ 编排中被调用的专家）。
     *
     * <p>供「工具调用明细」与「编排运行」两页的智能体筛选使用：既有 {@code /ai/agent/options}
     * 只返回**启用中**的智能体，{@code /ai/session/agent/options} 只返回**会话**出现过的智能体
     * ——专家不是会话智能体，故两者都取不到历史专家（尤其是已停用的历史专家）。</p>
     *
     * @return 智能体选项（含已停用，按主键升序）
     */
    List<AiAgentVo> queryReferencedAgentOptions();

}
