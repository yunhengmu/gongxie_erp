package org.dromara.ai.core.route;

import java.util.Optional;

/**
 * 路由判断端口（docs/21 §5.1）：把「从候选中选谁」这一步抽象为可替换能力。
 *
 * <p>上层只依赖本端口，不感知决策模型 / 厂商细节；实现类内部承担请求构造、调用、校验与异常归一化。
 * 便于单测打桩，也便于后续换模型或换厂商。</p>
 *
 * <p><b>契约</b>：判定不可用时返回 {@link Optional#empty()}，<b>绝不抛出异常</b>——
 * 判断是「增强通道」而非「必经通道」，任何失败都不得新增失败面（口径 P3）。</p>
 *
 * @author BizBuddy
 */
public interface RouteJudge {

    /**
     * 对一次路由判断请求作出判定。
     *
     * @param request 判断请求（{@code state} + 候选清单）
     * @return 判定结果；不可用时返回 {@link Optional#empty()}，不抛异常
     */
    Optional<RouteJudgeVerdict> judge(RouteJudgeRequest request);

}