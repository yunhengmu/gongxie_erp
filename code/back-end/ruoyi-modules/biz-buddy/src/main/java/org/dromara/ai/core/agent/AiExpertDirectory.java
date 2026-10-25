package org.dromara.ai.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.mapper.AiExpertAgentMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 专家名录（REQ-AI-30 / REQ-AI-33）：**每轮现算**专家清单。
 *
 * <p>拆表后只查 {@code ai_expert_agent}，并按用途细分三个查询：</p>
 * <ul>
 *   <li>{@link #candidates}：**自动候选**（{@code status=0} ∧ 可见 ∧ {@code auto_route='1'}）——注入路由提示词、
 *       作为 {@code expert_agent_key} 白名单；</li>
 *   <li>{@link #visibleExperts}：**全部可见专家**（不过滤 {@code auto_route}）——供工作台「指定专家」下拉；</li>
 *   <li>{@link #visibleExpert}：按主键取可见专家（不过滤 {@code auto_route}）——供 pin 校验与专家名解析。</li>
 * </ul>
 *
 * <p>可见范围口径：0 公开 / 1 本部门 / 2 仅本人（与 {@code AiAgentServiceImpl#queryOptions} 一致）。</p>
 *
 * <p><b>为什么不能用 {@code LoginHelper}</b>：本查询在编排链路里执行（Reactor 线程），
 * Sa-Token 线程上下文与 RuoYi 的数据权限处理器（{@code PlusDataPermissionHandler}）在此均不可用
 * ——后者会在无登录上下文时抛空指针（见 docs/00 已登记踩坑）。故用户与部门标识由调用方
 * **显式传入**，查询只走无 {@code @DataPermission} 的链式构造器。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AiExpertDirectory {

    /**
     * 可见范围：公开
     */
    private static final String SCOPE_ALL = "0";

    /**
     * 可见范围：本部门
     */
    private static final String SCOPE_DEPT = "1";

    /**
     * 可见范围：仅本人
     */
    private static final String SCOPE_SELF = "2";

    /**
     * 状态：正常
     */
    private static final String STATUS_NORMAL = "0";

    private final AiExpertAgentMapper expertMapper;

    /**
     * 取提问用户当前可见的**自动候选**专家清单（{@code auto_route='1'}）。
     *
     * @param userId 提问用户主键
     * @param deptId 提问用户部门主键
     * @return 候选专家清单（按主键升序，保证顺序稳定）
     */
    public List<AiExpertCandidate> candidates(Long userId, Long deptId) {
        LambdaQueryWrapper<AiExpertAgent> lqw = visibleWrapper(userId, deptId);
        lqw.eq(AiExpertAgent::getAutoRoute, AiExpertAgent.AUTO_ROUTE_ON);
        return expertMapper.selectList(lqw).stream().map(this::toCandidate).toList();
    }

    /**
     * 取提问用户当前可见的**全部**专家清单（**不过滤** {@code auto_route}）。
     *
     * <p>供工作台「指定专家」下拉：{@code auto_route='0'} 的专家只能被用户手动指定，故必须可见。</p>
     *
     * @param userId 提问用户主键
     * @param deptId 提问用户部门主键
     * @return 可见专家清单（按主键升序）
     */
    public List<AiExpertCandidate> visibleExperts(Long userId, Long deptId) {
        return expertMapper.selectList(visibleWrapper(userId, deptId)).stream().map(this::toCandidate).toList();
    }

    /**
     * 按主键取提问用户可见的专家（**不过滤** {@code auto_route}）。
     *
     * <p>用于「指定专家」的可见性校验与专家名解析：{@code auto_route='0'} 的专家不在自动候选清单里，
     * 但仍可被用户手动指定（见 docs/18）。</p>
     *
     * @param userId  提问用户主键
     * @param deptId  提问用户部门主键
     * @param agentId 专家主键
     * @return 可见专家实体；不满足「启用 ∩ 可见」时返回 null
     */
    public AiExpertAgent visibleExpert(Long userId, Long deptId, Long agentId) {
        if (agentId == null) {
            return null;
        }
        LambdaQueryWrapper<AiExpertAgent> lqw = visibleWrapper(userId, deptId);
        lqw.eq(AiExpertAgent::getId, agentId);
        return expertMapper.selectOne(lqw);
    }

    /**
     * 按主键取提问用户可见的专家候选（**不过滤** {@code auto_route}）。
     *
     * <p>供编排上下文放入 {@code route.pinnedExpert}：{@code auto_route='0'} 的专家不在自动候选清单内，
     * 但其专家名仍需正确展示（否则回归 B2：卡片回落兜底文案）。</p>
     *
     * @param userId  提问用户主键
     * @param deptId  提问用户部门主键
     * @param agentId 专家主键
     * @return 可见专家候选；不可见时返回 null
     */
    public AiExpertCandidate visibleCandidate(Long userId, Long deptId, Long agentId) {
        AiExpertAgent agent = visibleExpert(userId, deptId, agentId);
        return agent == null ? null : toCandidate(agent);
    }

    /**
     * 取专家定义（装配与元数据展示共用；不校验启用与可见性）。
     *
     * @param agentId 专家主键
     * @return 专家定义；不存在或已删除时返回 null
     */
    public AiExpertAgent definition(Long agentId) {
        return agentId == null ? null : expertMapper.selectById(agentId);
    }

    /**
     * 构建「启用 ∩ 可见」公共条件（按主键升序）。
     *
     * @param userId 提问用户主键
     * @param deptId 提问用户部门主键
     * @return 查询条件包装器
     */
    private LambdaQueryWrapper<AiExpertAgent> visibleWrapper(Long userId, Long deptId) {
        LambdaQueryWrapper<AiExpertAgent> lqw = Wrappers.lambdaQuery();
        lqw.eq(AiExpertAgent::getStatus, STATUS_NORMAL);
        lqw.and(w -> w.eq(AiExpertAgent::getVisibleScope, SCOPE_ALL)
            .or(o -> o.eq(AiExpertAgent::getVisibleScope, SCOPE_DEPT).eq(AiExpertAgent::getOwnerDeptId, deptId))
            .or(o -> o.eq(AiExpertAgent::getVisibleScope, SCOPE_SELF).eq(AiExpertAgent::getCreateBy, userId)));
        lqw.orderByAsc(AiExpertAgent::getId);
        return lqw;
    }

    /**
     * 实体转候选（能力描述为空回落 description）。
     *
     * @param agent 专家实体
     * @return 候选专家
     */
    private AiExpertCandidate toCandidate(AiExpertAgent agent) {
        // 专家直答修订后不再携带领域标签与输出契约
        return AiExpertCandidate.of(agent.getId(), agent.getAgentCode(), agent.getAgentName(),
            agent.getCapabilityDesc(), agent.getDescription());
    }
}
