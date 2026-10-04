package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 长期记忆 Controller（REQ-AI-09）。
 *
 * <p>记忆按用户隔离存放在智能体工作区内，本接口提供运行期配置、记忆文件查看与编辑、手动触发合并。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/memory")
public class AiMemoryController extends BaseController {

    private final IAiMemoryService memoryService;

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 查询当前生效的长期记忆配置
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/config")
    public R<AiMemoryConfigBo> getConfig() {
        return R.ok(runtimeConfigStore.memory());
    }

    /**
     * 保存长期记忆配置（运行期生效，重启后仍保留）
     */
    @SaCheckPermission("ai:memory:edit")
    @Log(title = "AI长期记忆配置", businessType = BusinessType.UPDATE)
    @PutMapping("/config")
    public R<Void> updateConfig(@Validated @RequestBody AiMemoryConfigBo bo) {
        runtimeConfigStore.updateMemory(bo);
        return R.ok();
    }

    /**
     * 查询指定智能体与用户下的记忆文件清单
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/files")
    public R<List<AiMemoryFileVo>> listFiles(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                             @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryService.listFiles(agentId, userId));
    }

    /**
     * 读取记忆文件内容
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     * @param path    工作区相对路径
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/content")
    public R<AiMemoryContentVo> readContent(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                            @NotNull(message = "用户不能为空") @RequestParam Long userId,
                                            @NotNull(message = "文件路径不能为空") @RequestParam String path) {
        return R.ok(memoryService.readContent(agentId, userId, path));
    }

    /**
     * 保存记忆文件内容（人工编辑）
     */
    @SaCheckPermission("ai:memory:edit")
    @Log(title = "AI长期记忆文件", businessType = BusinessType.UPDATE)
    @PutMapping("/content")
    public R<Void> writeContent(@Validated @RequestBody AiMemoryContentBo bo) {
        memoryService.writeContent(bo);
        return R.ok();
    }

    /**
     * 手动触发记忆合并（把每日流水合并进 MEMORY.md）
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:consolidate")
    @Log(title = "AI长期记忆合并", businessType = BusinessType.OTHER)
    @PostMapping("/consolidate")
    public R<Void> consolidate(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                               @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        memoryService.consolidate(agentId, userId);
        return R.ok();
    }

}