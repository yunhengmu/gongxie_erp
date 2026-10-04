package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpClientVo;
import org.dromara.ai.service.IAiMcpClientService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * MCP 客户端（外部 MCP Server 连接）Controller（REQ-AI-16）。
 *
 * <p>配置为平台级：启用后全部智能体装配时接入该 Server 的工具，暴露面由工具白名单收敛；
 * 仅支持 {@code sse} / {@code http} 传输（不放开 stdio）。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/mcp/client")
public class AiMcpClientController extends BaseController {

    private final IAiMcpClientService mcpClientService;

    /**
     * 查询外部 MCP 连接列表
     */
    @SaCheckPermission("ai:mcp:client:query")
    @GetMapping("/list")
    public R<PageResult<AiMcpClientVo>> list(@Validated(QueryGroup.class) AiMcpClientBo bo, PageQuery pageQuery) {
        return R.ok(mcpClientService.queryPageList(bo, pageQuery));
    }

    /**
     * 获取外部 MCP 连接详情
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:mcp:client:query")
    @GetMapping("/{id}")
    public R<AiMcpClientVo> getInfo(@NotNull(message = "主键不能为空")
                                    @PathVariable("id") Long id) {
        return R.ok(mcpClientService.queryById(id));
    }

    /**
     * 新增外部 MCP 连接
     */
    @SaCheckPermission("ai:mcp:client:add")
    @Log(title = "AI MCP 客户端", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiMcpClientBo bo) {
        return toAjax(mcpClientService.insertByBo(bo));
    }

    /**
     * 修改外部 MCP 连接
     */
    @SaCheckPermission("ai:mcp:client:edit")
    @Log(title = "AI MCP 客户端", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiMcpClientBo bo) {
        return toAjax(mcpClientService.updateByBo(bo));
    }

    /**
     * 删除外部 MCP 连接
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:mcp:client:remove")
    @Log(title = "AI MCP 客户端", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(mcpClientService.deleteWithValidByIds(Arrays.asList(ids), true));
    }

    /**
     * 测试连接并列出该 Server 声明的工具（用于配置工具白名单）
     */
    @SaCheckPermission("ai:mcp:client:query")
    @PostMapping("/test")
    public R<AiMcpClientTestVo> test(@RequestBody AiMcpClientBo bo) {
        return R.ok(mcpClientService.testConnection(bo));
    }

}