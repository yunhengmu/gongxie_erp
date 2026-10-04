package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceContentBo;
import org.dromara.ai.domain.vo.AiWorkspaceContentVo;
import org.dromara.ai.domain.vo.AiWorkspaceFileVo;
import org.dromara.ai.service.IAiWorkspaceService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 工作区文件 Controller（REQ-AI-08）。
 *
 * <p>工作区文件（人格与行为约定 AGENTS.md + 附加上下文文件）按用户覆盖存放于智能体工作区，
 * 本接口提供运行期配置、文件清单与分层内容的查看与编辑、以及清除用户覆盖。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/workspace")
public class AiWorkspaceController extends BaseController {

    private final IAiWorkspaceService workspaceService;

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 查询当前生效的工作区上下文配置
     */
    @SaCheckPermission("ai:workspace:query")
    @GetMapping("/config")
    public R<AiWorkspaceConfigBo> getConfig() {
        return R.ok(runtimeConfigStore.workspace());
    }

    /**
     * 保存工作区上下文配置（运行期生效，重启后仍保留）
     */
    @SaCheckPermission("ai:workspace:edit")
    @Log(title = "AI工作区上下文配置", businessType = BusinessType.UPDATE)
    @PutMapping("/config")
    public R<Void> updateConfig(@Validated @RequestBody AiWorkspaceConfigBo bo) {
        runtimeConfigStore.updateWorkspace(bo);
        return R.ok();
    }

    /**
     * 查询指定智能体与用户下的工作区文件清单
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     */
    @SaCheckPermission("ai:workspace:query")
    @GetMapping("/files")
    public R<List<AiWorkspaceFileVo>> listFiles(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                                @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(workspaceService.listFiles(agentId, userId));
    }

    /**
     * 读取指定层的工作区文件内容
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @param path    工作区相对路径
     * @param scope   层来源（SHARED 共享默认 / USER 用户覆盖）
     */
    @SaCheckPermission("ai:workspace:query")
    @GetMapping("/content")
    public R<AiWorkspaceContentVo> readContent(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                               @NotNull(message = "用户不能为空") @RequestParam Long userId,
                                               @NotBlank(message = "文件路径不能为空") @RequestParam String path,
                                               @NotBlank(message = "写入层不能为空") @RequestParam String scope) {
        return R.ok(workspaceService.readContent(agentId, userId, path, scope));
    }

    /**
     * 保存指定层的工作区文件内容（人工编辑）
     */
    @SaCheckPermission("ai:workspace:edit")
    @Log(title = "AI工作区文件", businessType = BusinessType.UPDATE)
    @PutMapping("/content")
    public R<Void> writeContent(@Validated @RequestBody AiWorkspaceContentBo bo) {
        workspaceService.writeContent(bo);
        return R.ok();
    }

    /**
     * 清除用户覆盖，使该文件回落共享默认
     *
     * @param agentId 智能体主键
     * @param userId  用户主键
     * @param path    工作区相对路径
     */
    @SaCheckPermission("ai:workspace:edit")
    @Log(title = "AI工作区文件用户覆盖", businessType = BusinessType.DELETE)
    @DeleteMapping("/content")
    public R<Void> resetUserOverride(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                     @NotNull(message = "用户不能为空") @RequestParam Long userId,
                                     @NotBlank(message = "文件路径不能为空") @RequestParam String path) {
        workspaceService.resetUserOverride(agentId, userId, path);
        return R.ok();
    }

}