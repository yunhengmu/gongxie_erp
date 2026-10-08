package org.dromara.ai.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiExpertPromptBo;
import org.dromara.ai.domain.vo.AiExpertPromptVo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.service.IAiExpertPromptService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 专家提示词辅助生成 Service 实现（REQ-AI-33 补强）。
 *
 * <p>复用「模型连通性测试」同一条 OpenAI 兼容调用链路（Hutool 直连
 * {@code /chat/completions}、密钥经 {@link IAiModelConfigService#decryptApiKey} 解密），
 * 以一次模型调用把少量硬信息补全为「描述 / 系统提示词」两项建议值。</p>
 *
 * <p><b>为何不写行为纪律</b>：专家直答、写操作必须真调用、DENIED 不重试等纪律由平台在
 * 编排与装配阶段强制注入，故生成器只让模型产出「领域知识与口径」，避免提示词冗长且与平台口径冲突。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiExpertPromptServiceImpl implements IAiExpertPromptService {

    /**
     * 对话模型类型
     */
    private static final String TYPE_CHAT = "chat";

    /**
     * 生成器系统指令：只输出严格 JSON，且只写「领域知识与口径」
     */
    private static final String SYSTEM_INSTRUCTION = """
        你是企业 AI 平台的「专家配置助手」。用户会给出待创建专家的少量信息（名称、领域一句话、\
        将绑定的业务工具、可检索的知识库、是否允许写操作）。
        请据此产出该专家的配置，**只输出一个 JSON 对象**，不要输出任何解释文字，也不要使用 Markdown 代码围栏。
        JSON 结构如下（两个字段均为字符串）：
        {"description":"一句话职责说明，不超过 40 字","sysPrompt":"系统提示词正文，300~800 字"}

        各字段要求：
        1. description：一句话说清该专家负责什么、擅长什么；这段文字会作为入口智能体候选清单里的「擅长」\
        （形如「名称=财务专家 | 擅长=…」），用于判断「用户的提问该不该交给它」，故要具体、可判别。
        2. sysPrompt：该专家的系统提示词正文，**只写该领域的知识、判断口径、常用术语/字段、处理步骤与输出格式偏好**。

        sysPrompt 的硬性约束：
        - **不要写行为纪律**：平台已统一强制注入（专家直接作答、写操作必须真实调用工具、被拒绝不得重试、\
        重复执行去重、暂停后续跑等），重复书写只会让提示词变长且可能与平台口径冲突。
        - 必须写明**能力边界**（哪些问题不在职责范围内）与**拒答口径**（超出范围或资料不足时如何回应）。
        - 若给出了知识库，写明「优先依据检索到的企业资料作答、无依据时如实说明」；**未给出知识库时，\
        不要声称会检索知识库**。
        - 若绑定了写操作工具，写明「执行写操作前先向用户确认关键参数，不可回滚的操作先说明后果」；\
        若只有只读工具，**不要承诺会修改数据**。
        """;

    private final IAiModelConfigService modelConfigService;

    private final AiProperties aiProperties;

    @Override
    public AiExpertPromptVo generate(AiExpertPromptBo bo) {
        AiExpertPromptVo result = new AiExpertPromptVo();
        long start = System.currentTimeMillis();
        try {
            AiModelConfigVo model = resolveModel(bo.getModelId());
            String apiKey = modelConfigService.decryptApiKey(model.getId());
            if (StringUtils.isBlank(apiKey)) {
                throw new ServiceException("所用模型未配置 API 密钥，请先到「模型配置」补齐密钥后再试");
            }
            String url = resolveEndpoint(model.getBaseUrl());
            String body = buildRequestBody(model.getModelCode(), SYSTEM_INSTRUCTION, buildUserInstruction(bo));
            int timeout = model.getTimeoutSeconds() != null && model.getTimeoutSeconds() > 0
                ? model.getTimeoutSeconds() : aiProperties.getTestTimeoutSeconds();

            int status;
            String responseBody;
            try (HttpResponse response = HttpUtil.createPost(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout * 1000)
                .body(body)
                .execute()) {
                status = response.getStatus();
                responseBody = response.body();
            }

            if (status < 200 || status >= 300) {
                result.setSuccess(false);
                result.setMessage("模型返回 HTTP " + status + "：" + extractError(responseBody));
            } else {
                fillFromContent(result, extractChatContent(responseBody));
                result.setModelName(model.getConfigName());
                if (StringUtils.isBlank(result.getSysPrompt()) && StringUtils.isBlank(result.getDescription())) {
                    result.setSuccess(false);
                    result.setMessage("模型未返回有效内容，请稍后重试或更换模型");
                }
            }
        } catch (ServiceException e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        } catch (Exception e) {
            log.warn("专家提示词生成异常", e);
            result.setSuccess(false);
            result.setMessage("生成异常：" + e.getMessage());
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 解析用于生成的对话模型：优先入参，其次平台首个启用的对话模型。
     *
     * @param modelId 模型主键（可空）
     * @return 模型配置
     */
    private AiModelConfigVo resolveModel(Long modelId) {
        AiModelConfigVo model;
        if (modelId != null) {
            model = modelConfigService.queryById(modelId);
            if (model == null) {
                throw new ServiceException("所选模型不存在或已被删除");
            }
        } else {
            List<AiModelConfigVo> chats = modelConfigService.queryOptions(TYPE_CHAT);
            if (chats.isEmpty()) {
                throw new ServiceException("平台暂无启用的对话模型，请先到「模型配置」新增");
            }
            model = chats.get(0);
        }
        if (StringUtils.isBlank(model.getModelCode())) {
            throw new ServiceException("所用模型未配置模型标识，请先到「模型配置」补全");
        }
        String baseUrl = model.getBaseUrl();
        if (StringUtils.isBlank(baseUrl) || baseUrl.contains("{") || baseUrl.contains("}")) {
            throw new ServiceException("所用模型的服务地址无效，请先到「模型配置」补全");
        }
        return model;
    }

    /**
     * 拼装请求体（OpenAI 兼容 chat/completions）。
     *
     * @param modelCode 模型标识
     * @param system    系统指令
     * @param user      用户指令
     * @return 请求体 JSON
     */
    static String buildRequestBody(String modelCode, String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelCode);
        body.put("messages", List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user)));
        body.put("temperature", 0.4);
        return JsonUtils.toJsonString(body);
    }

    /**
     * 由硬信息拼装用户指令。
     *
     * @param bo 入参
     * @return 用户指令文本
     */
    static String buildUserInstruction(AiExpertPromptBo bo) {
        return "专家名称：" + orDash(bo.getAgentName()) + '\n'
            + "领域一句话：" + orDash(bo.getDomainHint()) + '\n'
            + "将绑定的业务工具：" + joinOrNone(bo.getToolNames()) + '\n'
            + "其中写操作工具：" + joinOrNone(bo.getWriteToolNames()) + '\n'
            + "可检索的知识库：" + joinOrNone(bo.getKbNames()) + '\n'
            + "是否允许写操作：" + (Boolean.TRUE.equals(bo.getWriteEnabled()) ? "允许" : "不允许");
    }

    /**
     * 从对话响应中提取首条回复正文。
     *
     * @param responseBody 响应体
     * @return 回复正文
     */
    static String extractChatContent(String responseBody) {
        JsonNode choices = JsonUtils.getJsonMapper().readTree(responseBody).path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new ServiceException("响应中未找到对话结果：" + abbreviate(responseBody));
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    /**
     * 解析模型输出的 JSON 并回填两项建议值。
     *
     * @param result  生成结果
     * @param content 模型回复正文（可能含代码围栏）
     */
    static void fillFromContent(AiExpertPromptVo result, String content) {
        JsonNode root = JsonUtils.getJsonMapper().readTree(stripFence(content));
        result.setDescription(text(root, "description"));
        result.setSysPrompt(text(root, "sysPrompt"));
        result.setSuccess(true);
        result.setMessage("生成成功，请核对后按需修改");
    }

    /**
     * 去掉可能的 Markdown 代码围栏并截取首个 JSON 对象。
     *
     * @param text 原文
     * @return 纯 JSON 文本
     */
    static String stripFence(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd >= 0) {
                trimmed = trimmed.substring(firstLineEnd + 1);
            }
            int fence = trimmed.lastIndexOf("```");
            if (fence >= 0) {
                trimmed = trimmed.substring(0, fence);
            }
            trimmed = trimmed.trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * 读取 JSON 字段为字符串（缺失或为空时回落空串）。
     *
     * @param root 根节点
     * @param key  字段名
     * @return 字段值
     */
    private static String text(JsonNode root, String key) {
        return root.path(key).asText("").trim();
    }

    /**
     * 拼接字符串列表；空集合返回「无」。
     *
     * @param values 值列表
     * @return 拼接文本
     */
    private static String joinOrNone(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        return String.join("、", values);
    }

    /**
     * 空值回落。
     *
     * @param value 原值
     * @return 原名或「未填写」
     */
    private static String orDash(String value) {
        return StringUtils.isBlank(value) ? "未填写" : value.trim();
    }

    /**
     * 拼接 OpenAI 兼容对话端点地址。
     *
     * @param baseUrl 服务地址
     * @return 完整端点
     */
    private String resolveEndpoint(String baseUrl) {
        String base = StringUtils.trim(baseUrl);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // DashScope 类地址统一走 OpenAI 兼容模式
        if (isDashScopeHost(base) && base.contains("/api/v1") && !base.contains("/compatible-mode")) {
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String suffix = "/chat/completions";
        return base.endsWith(suffix) ? base : base + suffix;
    }

    /**
     * 是否 DashScope（含新版百炼 maas 域名）地址。
     *
     * @param baseUrl 服务地址
     * @return 是否 DashScope
     */
    private boolean isDashScopeHost(String baseUrl) {
        String base = StringUtils.trim(baseUrl).toLowerCase(Locale.ROOT);
        return base.contains("dashscope.aliyuncs.com") || base.contains(".maas.aliyuncs.com");
    }

    /**
     * 从错误响应中提取可读信息。
     *
     * @param responseBody 响应体
     * @return 错误说明
     */
    private String extractError(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "服务端未返回内容";
        }
        try {
            JsonNode node = JsonUtils.getJsonMapper().readTree(responseBody);
            String message = node.path("error").path("message").asText("");
            if (StringUtils.isBlank(message)) {
                message = node.path("message").asText("");
            }
            if (StringUtils.isNotBlank(message)) {
                return abbreviate(message);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，直接截断原文
        }
        return abbreviate(responseBody);
    }

    /**
     * 截断过长文本。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
    }

}