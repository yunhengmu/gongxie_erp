package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.core.memory.spi.AiMemoryBackend;
import org.dromara.ai.core.memory.spi.MemoryBackendStats;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.bo.AiMemoryDeclaredBo;
import org.dromara.ai.domain.bo.AiMemoryRestoreBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryDeclaredVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.domain.vo.AiMemoryVersionVo;
import org.dromara.ai.service.IAiMemoryService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final AiMemoryBackend memoryBackend;

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

    /**
     * 归档超期流水（移动到记忆目录下的 archive/ 子目录）
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:archive")
    @Log(title = "AI长期记忆归档", businessType = BusinessType.OTHER)
    @PostMapping("/archive")
    public R<Integer> archive(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                              @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryService.archiveDaily(agentId, userId));
    }

    /**
     * 清理超期记忆文件与会话日志
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:cleanup")
    @Log(title = "AI长期记忆清理", businessType = BusinessType.OTHER)
    @PostMapping("/cleanup")
    public R<Integer> cleanup(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                              @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryService.cleanup(agentId, userId));
    }

    /**
     * 查询某用户的记忆规模与维护状态（记忆后端不可用时返回空统计，不影响页面）
     *
     * @param userId 记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/stats")
    public R<MemoryBackendStats> stats(@NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryBackend.stats(userId));
    }

    /**
     * 查询用户的显式记忆（用户明确要求记住的条目）
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/declared")
    public R<List<AiMemoryDeclaredVo>> listDeclared(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                                    @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryService.listDeclared(agentId, userId));
    }

    /**
     * 新增一条显式记忆（管理端人工补充，仍走净化）
     */
    @SaCheckPermission("ai:memory:edit")
    @Log(title = "AI显式记忆", businessType = BusinessType.INSERT)
    @PostMapping("/declared")
    public R<AiMemoryDeclaredVo> addDeclared(@Validated @RequestBody AiMemoryDeclaredBo bo) {
        return R.ok(memoryService.addDeclared(bo));
    }

    /**
     * 撤销一条显式记忆
     */
    @SaCheckPermission("ai:memory:edit")
    @Log(title = "AI显式记忆", businessType = BusinessType.DELETE)
    @DeleteMapping("/declared/{entryId}")
    public R<Boolean> revokeDeclared(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                     @NotNull(message = "用户不能为空") @RequestParam Long userId,
                                     @PathVariable String entryId) {
        return R.ok(memoryService.revokeDeclared(agentId, userId, entryId));
    }

    /**
     * 列出 MEMORY.md 历史版本
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/versions")
    public R<List<AiMemoryVersionVo>> listVersions(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                                   @NotNull(message = "用户不能为空") @RequestParam Long userId) {
        return R.ok(memoryService.listVersions(agentId, userId));
    }

    /**
     * 读取版本详情（含正文）
     */
    @SaCheckPermission("ai:memory:query")
    @GetMapping("/versions/{versionId}")
    public R<AiMemoryVersionVo> readVersion(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                            @NotNull(message = "用户不能为空") @RequestParam Long userId,
                                            @PathVariable String versionId) {
        return R.ok(memoryService.readVersion(agentId, userId, versionId));
    }

    /**
     * 恢复到指定版本（恢复前自动快照当前版本）
     */
    @SaCheckPermission("ai:memory:edit")
    @Log(title = "AI长期记忆版本恢复", businessType = BusinessType.UPDATE)
    @PostMapping("/versions/{versionId}/restore")
    public R<AiMemoryVersionVo> restoreVersion(@PathVariable String versionId,
                                               @Validated @RequestBody AiMemoryRestoreBo bo) {
        return R.ok(memoryService.restoreVersion(bo.getAgentId(), bo.getUserId(), versionId,
            bo.getMode(), bo.getNote()));
    }

    /**
     * 删除某用户的全部长期记忆（回滚 / 合规删除）
     *
     * @param agentId 智能体主键
     * @param userId  记忆归属用户主键
     */
    @SaCheckPermission("ai:memory:remove")
    @Log(title = "AI长期记忆删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{agentId}/{userId}")
    public R<Void> remove(@PathVariable Long agentId, @PathVariable Long userId) {
        memoryService.deleteAll(agentId, userId);
        return R.ok();
    }

}