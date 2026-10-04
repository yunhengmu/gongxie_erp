package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.bo.AiAgentTaskBo;
import org.dromara.ai.domain.vo.AiAgentTaskRunVo;
import org.dromara.ai.domain.vo.AiAgentTaskVo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiAgentTaskService;
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
import java.util.List;

/**
 * AI 定时任务 Controller
 *
 * <p>任务定义在本项目维护，调度由 SnailJob 承载（决策 D7）：SnailJob 侧只登记一个
 * 「派发」任务（每 60 秒触发一次），由应用按任务自身的 Cron 判定到点任务并执行。
 * 执行以「运行身份用户」落库会话与消息，产出即会话管理页可见的「后台任务结果」。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/task")
public class AiAgentTaskController extends BaseController {

    private final IAiAgentTaskService aiAgentTaskService;

    private final IAiAgentService aiAgentService;

    /**
     * 查询定时任务列表
     */
    @SaCheckPermission("ai:task:list")
    @GetMapping("/list")
    public R<PageResult<AiAgentTaskVo>> list(@Validated(QueryGroup.class) AiAgentTaskBo bo, PageQuery pageQuery) {
        return R.ok(aiAgentTaskService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的智能体选项（供任务选择运行的智能体）
     */
    @SaCheckPermission("ai:task:list")
    @GetMapping("/agent/options")
    public R<List<AiAgentVo>> agentOptions() {
        return R.ok(aiAgentService.queryOptions());
    }

    /**
     * 获取定时任务详情
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:task:query")
    @GetMapping("/{id}")
    public R<AiAgentTaskVo> getInfo(@NotNull(message = "主键不能为空")
                                    @PathVariable("id") Long id) {
        return R.ok(aiAgentTaskService.queryById(id));
    }

    /**
     * 查询任务执行记录（分页，倒序）
     *
     * @param taskId 任务主键
     */
    @SaCheckPermission("ai:task:query")
    @GetMapping("/{taskId}/runs")
    public R<PageResult<AiAgentTaskRunVo>> runs(@NotNull(message = "任务不能为空")
                                                @PathVariable("taskId") Long taskId,
                                                PageQuery pageQuery) {
        return R.ok(aiAgentTaskService.queryRunPageList(taskId, pageQuery));
    }

    /**
     * 新增定时任务
     */
    @SaCheckPermission("ai:task:add")
    @Log(title = "AI定时任务", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiAgentTaskBo bo) {
        return toAjax(aiAgentTaskService.insertByBo(bo));
    }

    /**
     * 修改定时任务
     */
    @SaCheckPermission("ai:task:edit")
    @Log(title = "AI定时任务", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiAgentTaskBo bo) {
        return toAjax(aiAgentTaskService.updateByBo(bo));
    }

    /**
     * 删除定时任务（逻辑删除，保留历史执行记录）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:task:remove")
    @Log(title = "AI定时任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(aiAgentTaskService.deleteWithValidByIds(Arrays.asList(ids)));
    }

    /**
     * 立即执行一次任务（手动触发，不影响 Cron 排期）
     *
     * @param taskId 任务主键
     */
    @SaCheckPermission("ai:task:run")
    @Log(title = "AI定时任务", businessType = BusinessType.OTHER)
    @PostMapping("/{taskId}/run")
    public R<Long> run(@NotNull(message = "任务不能为空")
                       @PathVariable("taskId") Long taskId) {
        return R.ok(aiAgentTaskService.runNow(taskId));
    }

}
