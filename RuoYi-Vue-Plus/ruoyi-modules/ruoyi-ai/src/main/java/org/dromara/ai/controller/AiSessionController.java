package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiSessionExportVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * AI 会话管理 Controller
 *
 * <p>面向治理场景：可按用户 / 智能体 / 时间检索全部会话（不限定归属用户），
 * 查看消息记录与工具调用明细，必要时终止、删除或导出留档。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/session")
@SaCheckPermission("ai:session:list")
public class AiSessionController extends BaseController {

    private final IAiSessionService aiSessionService;

    /**
     * 分页查询会话列表（全部用户）
     */
    @GetMapping("/list")
    public R<PageResult<AiSessionVo>> list(@Validated(QueryGroup.class) AiSessionBo bo, PageQuery pageQuery) {
        return R.ok(aiSessionService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询出现过会话的智能体选项（供按智能体筛选，含已停用）
     */
    @GetMapping("/agent/options")
    public R<List<AiAgentVo>> agentOptions() {
        return R.ok(aiSessionService.queryAgentOptions());
    }

    /**
     * 查询会话消息记录
     *
     * @param sessionId 会话主键
     */
    @SaCheckPermission("ai:session:query")
    @GetMapping("/{sessionId}/messages")
    public R<List<AiMessageVo>> messages(@NotNull(message = "会话不能为空")
                                         @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiSessionService.querySessionMessages(sessionId));
    }

    /**
     * 查询会话的工具调用明细
     *
     * @param sessionId 会话主键
     */
    @SaCheckPermission("ai:session:query")
    @GetMapping("/{sessionId}/toolCalls")
    public R<List<AiToolCallLogVo>> toolCalls(@NotNull(message = "会话不能为空")
                                              @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiSessionService.queryToolCalls(sessionId));
    }

    /**
     * 终止会话（已结束的会话不能再继续提问）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:session:edit")
    @Log(title = "AI会话管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{ids}/close")
    public R<Void> close(@NotEmpty(message = "主键不能为空")
                         @PathVariable Long[] ids) {
        return toAjax(aiSessionService.closeWithValidByIds(Arrays.asList(ids)));
    }

    /**
     * 删除会话（逻辑删除会话与其消息，并清理模型记忆）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:session:remove")
    @Log(title = "AI会话管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiSessionService.deleteWithValidByIds(Arrays.asList(ids)));
    }

    /**
     * 导出会话留档（按当前筛选条件）
     */
    @SaCheckPermission("ai:session:export")
    @Log(title = "AI会话管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(@Validated(QueryGroup.class) AiSessionBo bo, HttpServletResponse response) {
        List<AiSessionExportVo> list = aiSessionService.buildExportData(bo);
        ExcelBuilder.of(list, AiSessionExportVo.class).sheetName("会话数据").toResponse(response);
    }

}
