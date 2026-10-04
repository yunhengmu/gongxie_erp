package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiRouteRunBo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiRouteRunStatsVo;
import org.dromara.ai.domain.vo.AiRouteRunVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * AI 编排运行查询 Service 接口（REQ-AI-30）。
 *
 * <p>读取侧供「任务工作台 / 任务管理」按会话查看编排轨迹，以及管理面「编排运行」页
 * （列表 + 统计 + 详情）查看决策、任务片段、每次专家调用的契约 / 结果 / 耗时 / Token
 * 与**原始输出原文**。写入侧在编排链路内由 {@code AiRouteRecorder} 完成
 * （Reactor 线程，不走本接口以避免数据权限处理器）。</p>
 *
 * @author BizBuddy
 */
public interface IAiRouteService {

    /**
     * 查询某会话的全部编排运行（含专家调用明细），按时间倒序。
     *
     * @param sessionId 会话主键
     * @return 编排运行明细列表
     */
    List<AiRouteRunDetailVo> querySessionRuns(Long sessionId);

    /**
     * 分页查询编排运行（管理面，按筛选条件跨会话检索）。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果（含会话标题 / 入口智能体名 / 提问用户昵称 / 参与专家名 / Token 汇总）
     */
    PageResult<AiRouteRunVo> queryPageList(AiRouteRunBo bo, PageQuery pageQuery);

    /**
     * 查询编排运行详情（含全部专家调用明细与 Token 汇总）。
     *
     * @param id 编排主键
     * @return 详情视图对象
     */
    AiRouteRunDetailVo queryById(Long id);

    /**
     * 按与列表**同一筛选条件**统计编排运行（降级率 / 路由命中率 / 耗时与 Token 汇总）。
     *
     * @param bo 查询条件
     * @return 统计视图对象
     */
    AiRouteRunStatsVo queryStats(AiRouteRunBo bo);

}