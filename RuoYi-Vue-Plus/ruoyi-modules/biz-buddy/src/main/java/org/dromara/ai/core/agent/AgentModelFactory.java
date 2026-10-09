package org.dromara.ai.core.agent;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 智能体模型构造工厂：按模型配置构造对话模型与生成参数，并解析各可选模型槽位。
 *
 * <p>从 {@link AgentRegistry} 抽出，全部为无状态支撑方法：只读取模型配置表并解密密钥，
 * 不持有装配缓存与运行期状态，可在装配期被注册表直接调用。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentModelFactory {

    private final AiModelConfigMapper modelConfigMapper;

    private final IAiModelConfigService modelConfigService;

    /**
     * 解析智能体定义的压缩摘要模型（REQ-AI-06 槽位）。
     *
     * @param definition 智能体定义
     * @return 压缩摘要模型；不可用时返回 null
     */
    Model resolveSummaryModel(AiAgent definition) {
        return resolveSlotModel(definition, definition.getSummaryModelId(), "压缩摘要模型");
    }

    /**
     * 解析智能体定义的记忆抽取与合并模型（REQ-AI-06 槽位）。
     *
     * @param definition 智能体定义
     * @return 记忆模型；不可用时返回 null
     */
    Model resolveMemoryModel(AiAgent definition) {
        return resolveSlotModel(definition, definition.getMemoryModelId(), "记忆抽取与合并模型");
    }

    /**
     * 解析可选的模型槽位。
     *
     * <p>槽位为可选：未配置、已被删除或模型标识/地址不完整时返回 null，由框架回退主模型，
     * 避免因一个可选槽位配置不当导致整个智能体无法装配。</p>
     *
     * @param definition  智能体定义
     * @param slotModelId 槽位绑定的模型主键
     * @param slotLabel   槽位名称（仅用于日志）
     * @return 槽位模型；不可用时返回 null
     */
    private Model resolveSlotModel(AiAgent definition, Long slotModelId, String slotLabel) {
        if (slotModelId == null || slotModelId.equals(definition.getModelId())) {
            return null;
        }
        AiModelConfig config = modelConfigMapper.selectById(slotModelId);
        if (config == null || StringUtils.isBlank(config.getModelCode())
            || StringUtils.isBlank(config.getBaseUrl()) || config.getBaseUrl().contains("{")) {
            log.warn("{}不可用，回退主模型：agent={}, modelId={}", slotLabel, definition.getAgentCode(), slotModelId);
            return null;
        }
        log.info("{}：agent={}, model={}", slotLabel, definition.getAgentCode(), config.getModelCode());
        return buildChatModel(config);
    }

    /**
     * 取模型槽位的模型标识（未配置或已被删除时返回 null）。
     *
     * @param modelId 模型主键
     * @return 模型标识
     */
    String slotModelCode(Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModelConfig config = modelConfigMapper.selectById(modelId);
        return config == null ? null : config.getModelCode();
    }

    /**
     * 还原子智能体声明的模型标识为模型实例（供框架 {@code modelResolver} 回调）。
     *
     * <p>返回 {@code null} 时框架自动回退主模型，避免子智能体模型配置不当导致委派失败。</p>
     *
     * @param modelToken 形如 {@code bizbuddy-model:<主键>} 的标识
     * @return 模型实例；无法解析时返回 null
     */
    Model resolveSubagentModel(String modelToken) {
        if (StringUtils.isBlank(modelToken)
            || !modelToken.startsWith(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX)) {
            return null;
        }
        Long modelId;
        try {
            modelId = Long.valueOf(modelToken.substring(AgentRegistry.SUBAGENT_MODEL_TOKEN_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        AiModelConfig config = modelConfigMapper.selectById(modelId);
        if (config == null || !"0".equals(config.getStatus())
            || StringUtils.isBlank(config.getModelCode()) || StringUtils.isBlank(config.getBaseUrl())
            || config.getBaseUrl().contains("{")) {
            log.warn("子智能体模型不可用，回退主模型：modelId={}", modelId);
            return null;
        }
        return buildChatModel(config);
    }

    /**
     * 按模型配置构造对话模型。
     *
     * <p>优先走 AgentScope 的 {@link ModelProvider} SPI（模型 id 形如 {@code provider:modelCode}）：
     * 命中时可获得该提供商**专用的 formatter**、结构化输出默认值与**上下文窗口大小**
     * （例如 DeepSeek 使用 {@code DeepSeekFormatter} 且关闭原生结构化输出）。</p>
     *
     * <p>未命中则回退为显式构造 OpenAI 兼容客户端，以下两种情况必定回退，保证行为不劣化：
     * ① provider 未引入对应扩展模块（如 {@code dashscope} / {@code ollama} / {@code custom}）；
     * ② 密钥为空（如本地 Ollama 等免鉴权端点，SPI 提供商会因缺少 apiKey 直接抛异常）。</p>
     *
     * @param config 模型配置
     * @return 对话模型
     */
    Model buildChatModel(AiModelConfig config) {
        String baseUrl = resolveBaseUrl(config.getBaseUrl());
        String apiKey = modelConfigService.decryptApiKey(config.getId());
        GenerateOptions generateOptions = buildGenerateOptions(config);
        String provider = StringUtils.trim(config.getProvider());
        if (StringUtils.isNotBlank(provider) && StringUtils.isNotBlank(apiKey)) {
            String modelId = provider + ":" + StringUtils.trim(config.getModelCode());
            ModelCreationContext context = ModelCreationContext.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .stream(true)
                .component(GenerateOptions.class, generateOptions)
                .build();
            if (ModelRegistry.canResolve(modelId, context)) {
                log.info("模型解析命中提供商扩展：provider={}, modelId={}, baseUrl={}",
                    provider, modelId, baseUrl);
                return ModelRegistry.resolve(modelId, context);
            }
            log.info("提供商 {} 无可用扩展模块，回退为显式构造 OpenAI 兼容客户端：model={}",
                provider, config.getModelCode());
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
            .apiKey(apiKey)
            .modelName(config.getModelCode())
            .baseUrl(baseUrl)
            .stream(true);
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }
        return builder.build();
    }

    /**
     * 由模型配置构造生成参数（超时与重试）。
     *
     * <p>{@code ai_model_config.timeout_seconds} / {@code max_retries} 此前只落库未生效，
     * 现经 {@link ExecutionConfig} 接入模型调用；两项均未配置时返回 {@code null} 以保持默认行为。</p>
     *
     * @param config 模型配置
     * @return 生成参数；无可配置项时返回 null
     */
    private GenerateOptions buildGenerateOptions(AiModelConfig config) {
        boolean hasTimeout = config.getTimeoutSeconds() != null && config.getTimeoutSeconds() > 0;
        boolean hasRetries = config.getMaxRetries() != null && config.getMaxRetries() > 0;
        if (!hasTimeout && !hasRetries) {
            return null;
        }
        ExecutionConfig.Builder execution = ExecutionConfig.builder();
        if (hasTimeout) {
            execution.timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
        }
        if (hasRetries) {
            execution.maxAttempts(config.getMaxRetries() + 1);
        }
        log.info("模型超时与重试已接入：model={}, timeout={}s, maxAttempts={}",
            config.getModelCode(), hasTimeout ? config.getTimeoutSeconds() : "默认",
            hasRetries ? config.getMaxRetries() + 1 : 1);
        return GenerateOptions.builder().executionConfig(execution.build()).build();
    }

    /**
     * 校验并读取对话模型配置。
     *
     * @param modelId 模型主键
     * @return 模型配置
     */
    AiModelConfig resolveChatModel(Long modelId) {
        AiModelConfig config = modelId == null ? null : modelConfigMapper.selectById(modelId);
        if (config == null) {
            throw new ServiceException("智能体绑定的对话模型不存在或已被删除");
        }
        if (StringUtils.isBlank(config.getModelCode())) {
            throw new ServiceException("对话模型的模型标识为空，请先到「模型配置」中补全");
        }
        if (StringUtils.isBlank(config.getBaseUrl()) || config.getBaseUrl().contains("{")
            || config.getBaseUrl().contains("}")) {
            throw new ServiceException("对话模型的服务地址无效，请先到「模型配置」中补全");
        }
        return config;
    }

    /**
     * 规范化服务地址（配置若已含对话端点后缀，则去除后交给客户端拼接）。
     *
     * @param baseUrl 原始服务地址
     * @return 规范化后的根地址
     */
    private String resolveBaseUrl(String baseUrl) {
        String base = StringUtils.trim(baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.toLowerCase().endsWith(AgentRegistry.CHAT_ENDPOINT_SUFFIX)) {
            base = base.substring(0, base.length() - AgentRegistry.CHAT_ENDPOINT_SUFFIX.length());
        }
        return base;
    }

}
