package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiMessageAuditBo;
import org.dromara.ai.domain.bo.AiToolCallAuditBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageAuditExportVo;
import org.dromara.ai.domain.vo.AiMessageAuditVo;
import org.dromara.ai.domain.vo.AiToolCallAuditVo;
import org.dromara.ai.domain.vo.AiToolCallStatVo;
import org.dromara.ai.service.IAiAuditService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 审计 Controller（会话与消息审计、工具调用明细）
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/audit")
public class AiAuditController extends BaseController {

    private final IAiAuditService aiAuditService;

    /**
     * 分页查询会话消息（跨会话检索）
     */
    @SaCheckPermission("ai:audit:message")
    @GetMapping("/message/list")
    public R<PageResult<AiMessageAuditVo>> messageList(@Validated(QueryGroup.class) AiMessageAuditBo bo,
                                                       PageQuery pageQuery) {
        return R.ok(aiAuditService.queryMessagePage(bo, pageQuery));
    }

    /**
     * 导出会话消息（按当前筛选条件）
     */
    @SaCheckPermission("ai:audit:message:export")
    @Log(title = "AI消息审计", businessType = BusinessType.EXPORT)
    @PostMapping("/message/export")
    public void messageExport(@Validated(QueryGroup.class) AiMessageAuditBo bo, HttpServletResponse response) {
        List<AiMessageAuditExportVo> list = aiAuditService.buildMessageExport(bo);
        ExcelBuilder.of(list, AiMessageAuditExportVo.class).sheetName("消息审计").toResponse(response);
    }

    /**
     * 分页查询工具调用明细
     */
    @SaCheckPermission("ai:audit:toolcall")
    @GetMapping("/toolCall/list")
    public R<PageResult<AiToolCallAuditVo>> toolCallList(@Validated(QueryGroup.class) AiToolCallAuditBo bo,
                                                         PageQuery pageQuery) {
        return R.ok(aiAuditService.queryToolCallPage(bo, pageQuery));
    }

    /**
     * 按工具统计调用次数与失败率
     */
    @SaCheckPermission("ai:audit:toolcall")
    @GetMapping("/toolCall/stats")
    public R<List<AiToolCallStatVo>> toolCallStats(@Validated(QueryGroup.class) AiToolCallAuditBo bo) {
        return R.ok(aiAuditService.queryToolCallStats(bo));
    }

    /**
     * 查询「运行数据中出现过的智能体」选项（含已停用），供工具调用明细与编排运行两页的智能体筛选使用
     *
     * <p>专家不是会话智能体，故 {@code /ai/session/agent/options} 取不到；{@code /ai/agent/options}
     * 又只返回启用中的智能体。三个消费页共用本接口，权限沿用既有 OR 口径。</p>
     */
    @SaCheckPermission(value = {"ai:audit:toolcall", "ai:audit:route", "ai:audit:trace"}, mode = SaMode.OR)
    @GetMapping("/agent/options")
    public R<List<AiAgentVo>> agentOptions() {
        return R.ok(aiAuditService.queryReferencedAgentOptions());
    }

}
