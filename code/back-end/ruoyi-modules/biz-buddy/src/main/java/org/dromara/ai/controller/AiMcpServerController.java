package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiMcpExposedBo;
import org.dromara.ai.domain.bo.AiMcpServerConfigBo;
import org.dromara.ai.domain.vo.AiMcpServerToolVo;
import org.dromara.ai.domain.vo.AiMcpServerVo;
import org.dromara.ai.service.IAiMcpServerService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 服务端 Controller（REQ-AI-15）。
 *
 * <p>管理对外暴露给外部 AI 的工具清单与访问口令；出口端点为 {@code /mcp}（与主应用共用端口），
 * 只暴露**启用中的只读工具**，写入类工具不在出口范围内（出口无人工确认闸门）。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/mcp/server")
public class AiMcpServerController extends BaseController {

    private final IAiMcpServerService mcpServerService;

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 查询出口状态（端点、口令校验开关与口令、当前出口实际挂载的工具名）
     */
    @SaCheckPermission("ai:mcp:server:query")
    @GetMapping()
    public R<AiMcpServerVo> getServer() {
        return R.ok(mcpServerService.queryServer());
    }

    /**
     * 保存出口访问配置（口令校验开关与口令，运行期生效、重启后仍保留）
     */
    @SaCheckPermission("ai:mcp:server:edit")
    @Log(title = "AI MCP 出口配置", businessType = BusinessType.UPDATE)
    @PutMapping()
    public R<Void> updateServer(@Validated @RequestBody AiMcpServerConfigBo bo) {
        runtimeConfigStore.updateMcpServer(bo);
        return R.ok();
    }

    /**
     * 查询对外暴露候选工具清单（启用中的只读工具）与暴露状态
     */
    @SaCheckPermission("ai:mcp:server:query")
    @GetMapping("/tools")
    public R<List<AiMcpServerToolVo>> listTools() {
        return R.ok(mcpServerService.queryTools());
    }

    /**
     * 保存对外暴露清单（热生效，无需重启）
     */
    @SaCheckPermission("ai:mcp:server:edit")
    @Log(title = "AI MCP 出口暴露清单", businessType = BusinessType.UPDATE)
    @PutMapping("/tools")
    public R<Void> updateTools(@RequestBody AiMcpExposedBo bo) {
        mcpServerService.updateExposed(bo.getToolCodes());
        return R.ok();
    }

}