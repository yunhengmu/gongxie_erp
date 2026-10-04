package org.dromara.ai.core.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiSubagent;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 子智能体装配支撑：生成子智能体声明并解析其可继承的工具白名单（REQ-AI-04）。
 *
 * <p>从 {@link AgentRegistry} 抽出，全部为无状态纯函数，不持有装配缓存与实际模型实例
 * （声明中的模型仅以字符串标识承载，装配期由注册表注入的 {@code modelResolver} 还原）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class SubagentAssembler {

    /**
     * 生成子智能体声明（REQ-AI-04）。
     *
     * @param subagents 绑定的启用中子智能体
     * @param definition 智能体定义（提供子智能体默认模型槽位）
     * @param toolPool  可继承的只读工具池
     * @return 框架所需的声明列表
     */
    List<SubagentDeclaration> buildSubagentDeclarations(List<AiSubagent> subagents, AiAgent definition,
                                                        List<String> toolPool) {
        List<SubagentDeclaration> declarations = new ArrayList<>(subagents.size());
        for (AiSubagent subagent : subagents) {
            SubagentDeclaration.Builder decl = SubagentDeclaration.builder()
                .name(subagent.getName())
                .description(subagent.getDescription())
                .workspaceMode(AiSubagent.WORKSPACE_SHARED.equals(subagent.getWorkspaceMode())
                    ? WorkspaceMode.SHARED : WorkspaceMode.ISOLATED)
                .steps(subagent.getSteps() == null ? AiSubagent.DEFAULT_STEPS : subagent.getSteps())
                .tools(resolveSubagentTools(subagent, toolPool));
            if (StringUtils.isNotBlank(subagent.getSysPrompt())) {
                // 不走 workspace(Path)：提示词直接内联，避免为每个子智能体维护定义目录与 AGENTS.md
                decl.inlineAgentsBody(subagent.getSysPrompt());
            }
            // 模型优先取子智能体自身配置，其次取智能体的「子智能体默认模型」槽位（REQ-AI-06），
            // 都没有时不传 model（框架继承主模型）
            Long modelId = subagent.getModelId() != null ? subagent.getModelId() : definition.getSubagentModelId();
            if (modelId != null) {
                decl.model(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX + modelId);
            }
            declarations.add(decl.build());
        }
        return declarations;
    }

    /**
     * 解析子智能体实际生效的工具白名单。
     *
     * <p>声明的 {@code tools} 是「继承父级工具的白名单」：<b>空列表＝不过滤（继承父级全部工具）</b>，
     * 故此处必须始终给出非空列表——留空表示继承父级的只读工具池；若最终无任何工具可用，
     * 用不可能命中的占位名实现「零下放」，防止父级写入类工具被子智能体继承。</p>
     *
     * @param subagent 子智能体定义
     * @param toolPool 可继承的只读工具池
     * @return 非空的白名单列表
     */
    private List<String> resolveSubagentTools(AiSubagent subagent, List<String> toolPool) {
        List<String> requested = parseSubagentToolCodes(subagent.getToolCodes());
        List<String> effective = requested.isEmpty()
            ? toolPool
            // 兜底再交一次只读池：即使库中存在历史遗留的写入类编码（绕过服务层校验写入）也不会被下放
            : requested.stream().filter(toolPool::contains).toList();
        return effective.isEmpty() ? List.of(AgentRegistry.SUBAGENT_TOOL_NONE) : effective;
    }

    /**
     * 解析子智能体工具白名单（JSON 数组文本）。
     *
     * @param json JSON 数组文本
     * @return 工具编码列表（空或非法时返回空列表）
     */
    private List<String> parseSubagentToolCodes(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> codes = JsonUtils.parseArray(json, String.class);
            return codes == null ? List.of() : codes;
        } catch (Exception e) {
            log.warn("子智能体工具白名单解析失败，按留空处理：{}", json);
            return List.of();
        }
    }

}
