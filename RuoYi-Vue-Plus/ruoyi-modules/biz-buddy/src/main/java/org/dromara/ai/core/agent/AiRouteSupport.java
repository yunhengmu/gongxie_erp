package org.dromara.ai.core.agent;

import io.agentscope.core.skill.SkillFilter;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.AiTrace;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.service.IAiScenarioPackService;
import org.dromara.ai.service.IAiSkillService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 编排 / 运行期无状态辅助（技能白名单、场景包解析、链路来源、任务片段）。
 *
 * <p>从门面抽出的纯辅助集合：不持有对话状态、不产生副作用，供实时对话链路与无人值守链路共用。</p>
 *
 * @author BizBuddy
 */
@Component
@RequiredArgsConstructor
public class AiRouteSupport {

    private final IAiSkillService skillService;

    private final IAiScenarioPackService scenarioPackService;

    /**
     * 构造运行期技能白名单（REQ-AI-10）。
     *
     * <p>取「智能体绑定 ∪ 会话资源集技能 ∩ 技能启用 ∩ 提问用户可见」的技能标识；由于
     * {@code SkillFilter.only(...)} 是独立过滤器而非 overlay，它会**整体替换**装配期的
     * builder 级过滤器，而此处已与绑定 / 资源集集合求交，故语义等价且更严。</p>
     *
     * <p>注意框架 {@code SkillFilter} 的既定语义是「决定哪些技能出现在 system prompt」，
     * 不阻止按名加载技能内容；本项目据此把生效范围落在「模型可见面」，
     * 与知识库「检索链路按提问用户过滤」的口径边界见 docs/00 第 38 项。</p>
     *
     * @param agentId   智能体主键
     * @param resources 会话级资源集（REQ-AI-32；可空）
     * @param userId    提问用户主键
     * @return 技能过滤器
     */
    SkillFilter runtimeSkillFilter(Long agentId, AiSessionResourceSet resources, Long userId) {
        List<Long> extraSkillIds = resources == null ? List.of() : resources.mergedSkillIds();
        List<String> names = skillService.resolveRuntimeSkillFilterKeys(agentId, extraSkillIds, userId);
        return names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(new String[0]));
    }

    /**
     * 构造运行期技能白名单（兼容路径：无人值守链路按单场景包）。
     *
     * @param agentId 智能体主键
     * @param pack    会话所选场景包（可为 null）
     * @param userId  提问用户主键
     * @return 技能过滤器
     */
    SkillFilter runtimeSkillFilter(Long agentId, AiScenarioPack pack, Long userId) {
        List<String> names = skillService.resolveRuntimeSkillFilterKeys(agentId, packSkillIds(pack), userId);
        return names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(new String[0]));
    }

    /**
     * 按会话固化的 {@code scenario_pack_id} 取场景包；为空 / 已停用 / 已删除时按「无包」处理。
     *
     * @param session 会话实体
     * @return 场景包；不可用时返回 null
     */
    AiScenarioPack resolveScenarioPack(AiSession session) {
        return session == null ? null : scenarioPackService.getEnabledPackOrNull(session.getScenarioPackId());
    }

    /**
     * 取场景包内技能主键（未选包时为空）。
     *
     * @param pack 场景包（可为 null）
     * @return 技能主键集合
     */
    List<Long> packSkillIds(AiScenarioPack pack) {
        return pack == null ? List.of() : scenarioPackService.queryPackSkillIds(pack.getId());
    }

    /**
     * 由会话来源推导链路追踪来源（无人值守链路：定时任务 / 渠道接入）。
     *
     * <p>两者的处理口径完全一致（无登录上下文、{@code DONT_ASK}、写工具一律拒绝），
     * 仅来源标记不同，便于链路追踪页按来源区分排障。</p>
     *
     * @param session 会话实体
     * @return 链路追踪来源
     */
    static String resolveTraceSource(AiSession session) {
        return AiSession.SOURCE_CHANNEL.equals(session.getSource()) ? AiTrace.SOURCE_CHANNEL : AiTrace.SOURCE_TASK;
    }

    /**
     * 构造指定路由的 {@code task_context}（P7：**取用户原话**，不做额外的任务提炼调用）。
     *
     * <p>按需附带必要槽位（时区 / 时间窗 / 语言 / 任务线）与提问用户 / 部门，使专家无需追问；
     * 用户与部门仅作上下文陈述，**授权仍由工具侧 {@code AiDataScopeResolver} 判定**。</p>
     *
     * @param question 用户原话
     * @param slots    继承槽位（可空）
     * @param userId   提问用户主键
     * @param deptId   提问用户部门主键（可空）
     * @return 任务片段文本
     */
    static String taskContext(String question, AiSlotSet slots, Long userId, Long deptId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【用户原话】\n").append(question == null ? "" : question).append('\n');
        if (slots != null && !slots.isEmpty()) {
            sb.append("\n【继承槽位（直接采用，无需向用户追问）】\n").append(slots.render()).append('\n');
        }
        sb.append("\n【上下文】提问用户ID=").append(userId);
        if (deptId != null) {
            sb.append("；部门ID=").append(deptId);
        }
        return sb.toString();
    }
}
