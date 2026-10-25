package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiAgentTaskBo;
import org.dromara.ai.domain.vo.AiAgentTaskRunVo;
import org.dromara.ai.domain.vo.AiAgentTaskVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;

/**
 * AI 定时任务 Service 接口
 *
 * <p>调度由应用内调度器承载：每 60 秒调用 {@link #dispatchDueTasks()}，由本服务按
 * {@code cron_expression} 判定到点任务并提交执行。</p>
 *
 * @author BizBuddy
 */
public interface IAiAgentTaskService {

    /**
     * 分页查询定时任务列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiAgentTaskVo> queryPageList(AiAgentTaskBo bo, PageQuery pageQuery);

    /**
     * 查询定时任务详情。
     *
     * @param id 主键
     * @return 任务视图对象
     */
    AiAgentTaskVo queryById(Long id);

    /**
     * 新增定时任务。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiAgentTaskBo bo);

    /**
     * 修改定时任务。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiAgentTaskBo bo);

    /**
     * 按主键集合删除定时任务（逻辑删除，保留历史执行记录）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 立即执行一次任务（手动触发，不影响 Cron 排期）。
     *
     * @param taskId 任务主键
     * @return 执行记录主键
     */
    Long runNow(Long taskId);

    /**
     * 分页查询任务执行记录（按任务，倒序）。
     *
     * @param taskId    任务主键
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiAgentTaskRunVo> queryRunPageList(Long taskId, PageQuery pageQuery);

    /**
     * 派发到点任务（由应用内调度器周期性调用）。
     *
     * <p>以「计划执行时间」做原子抢占，抢占成功者才提交执行，多实例部署下也不会重复执行。</p>
     *
     * @return 本次触发（提交执行）的任务数
     */
    int dispatchDueTasks();

}
