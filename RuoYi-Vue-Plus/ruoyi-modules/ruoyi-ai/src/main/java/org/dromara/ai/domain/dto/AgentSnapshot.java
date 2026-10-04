package org.dromara.ai.domain.dto;

import java.util.Map;

/**
 * 智能体装配快照（REQ-AI-28 链路追踪）。
 *
 * <p>在<b>装配期</b>由 {@code AgentRegistry} 生成并随装配结果一起缓存：模型版本取该次装配
 * 实际构造的模型配置，上下文清单取该次装配实际生效的工具 / 知识库 / 技能 / 子智能体 /
 * 外部 MCP 与各运行期策略。链路落库时直接取用，因此**事后修改定义或配置不会改写历史链路**。</p>
 *
 * @param modelId             主对话模型主键
 * @param modelCode           主对话模型标识
 * @param modelProvider       主对话模型提供商
 * @param summaryModelCode    压缩摘要模型标识（未配置为空）
 * @param memoryModelCode     记忆抽取与合并模型标识（未配置为空）
 * @param subagentModelCode   子智能体默认模型标识（未配置为空）
 * @param context             上下文清单（结构化，可直接序列化为 JSON）
 * @author BizBuddy
 */
public record AgentSnapshot(Long modelId,
                            String modelCode,
                            String modelProvider,
                            String summaryModelCode,
                            String memoryModelCode,
                            String subagentModelCode,
                            Map<String, Object> context) {
}