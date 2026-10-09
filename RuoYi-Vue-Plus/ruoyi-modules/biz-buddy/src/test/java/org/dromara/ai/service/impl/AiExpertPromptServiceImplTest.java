package org.dromara.ai.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.bo.AiExpertPromptBo;
import org.dromara.ai.domain.vo.AiExpertPromptVo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.service.IAiModelConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link AiExpertPromptServiceImpl} 单元测试：模型解析、密钥校验、请求体拼装、响应解析与去围栏。
 */
@DisplayName("AI 专家提示词辅助生成服务")
class AiExpertPromptServiceImplTest {

    private final IAiModelConfigService modelConfigService = mock(IAiModelConfigService.class);
    private final AiProperties aiProperties = new AiProperties();
    private final AiExpertPromptServiceImpl service =
        new AiExpertPromptServiceImpl(modelConfigService, aiProperties);

    @Test
    @DisplayName("生成成功解析两项建议值")
    void shouldGenerateSuccessfully() {
        when(modelConfigService.queryById(1L)).thenReturn(chatModel("deepseek-v4-flash-0731"));
        when(modelConfigService.decryptApiKey(1L)).thenReturn("sk-1");
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            String content = "{\"description\":\"财务专家，擅长报销与预算口径\",\"sysPrompt\":\"你负责财务口径\"}";
            stubHttp(http, 200, chatBody(content));
            AiExpertPromptBo bo = new AiExpertPromptBo();
            bo.setModelId(1L);
            bo.setAgentName("财务专家");
            bo.setDomainHint("负责报销");

            AiExpertPromptVo result = service.generate(bo);

            assertTrue(result.getSuccess());
            assertEquals("财务专家，擅长报销与预算口径", result.getDescription());
            assertEquals("你负责财务口径", result.getSysPrompt());
            assertEquals("deepseek-v4-flash-0731", result.getModelName());
        }
    }

    @Test
    @DisplayName("模型密钥为空时生成失败")
    void shouldFailWhenApiKeyBlank() {
        when(modelConfigService.queryById(1L)).thenReturn(chatModel("deepseek-v4-flash-0731"));
        when(modelConfigService.decryptApiKey(1L)).thenReturn("");

        AiExpertPromptVo result = service.generate(bo(1L));

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("API 密钥"));
    }

    @Test
    @DisplayName("指定模型不存在时生成失败")
    void shouldFailWhenModelMissing() {
        when(modelConfigService.queryById(9L)).thenReturn(null);

        AiExpertPromptVo result = service.generate(bo(9L));

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("未指定模型时回落首个启用对话模型；无可用模型则失败")
    void shouldFallbackOrFailOnNoChatModel() {
        when(modelConfigService.queryOptions("chat")).thenReturn(List.of());
        AiExpertPromptVo noModel = service.generate(new AiExpertPromptBo());
        assertFalse(noModel.getSuccess());
        assertTrue(noModel.getMessage().contains("暂无启用的对话模型"));

        when(modelConfigService.queryOptions("chat")).thenReturn(List.of(chatModel("deepseek-v4-flash-0731")));
        when(modelConfigService.decryptApiKey(1L)).thenReturn("sk-1");
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            stubHttp(http, 200, chatBody("{\"sysPrompt\":\"内容\"}"));
            AiExpertPromptVo ok = service.generate(new AiExpertPromptBo());
            assertTrue(ok.getSuccess());
            assertEquals("内容", ok.getSysPrompt());
        }
    }

    @Test
    @DisplayName("非 2xx 响应时生成失败并给出 HTTP 状态")
    void shouldFailOnNon2xx() {
        when(modelConfigService.queryById(1L)).thenReturn(chatModel("deepseek-v4-flash-0731"));
        when(modelConfigService.decryptApiKey(1L)).thenReturn("sk-1");
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            stubHttp(http, 401, "{\"error\":{\"message\":\"invalid key\"}}");
            AiExpertPromptVo result = service.generate(bo(1L));
            assertFalse(result.getSuccess());
            assertTrue(result.getMessage().contains("HTTP 401"));
        }
    }

    @Test
    @DisplayName("去掉 Markdown 围栏并截取首个 JSON 对象")
    void shouldStripFence() {
        assertEquals("{\"a\":1}", AiExpertPromptServiceImpl.stripFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", AiExpertPromptServiceImpl.stripFence("说明文字 {\"a\":1} 结束"));
    }

    @Test
    @DisplayName("用户指令对空集合回落为「无」")
    void shouldRenderUserInstruction() {
        AiExpertPromptBo bo = new AiExpertPromptBo();
        bo.setAgentName("财务专家");
        bo.setWriteEnabled(false);
        String text = AiExpertPromptServiceImpl.buildUserInstruction(bo);
        assertTrue(text.contains("专家名称：财务专家"));
        assertTrue(text.contains("将绑定的业务工具：无"));
        assertTrue(text.contains("是否允许写操作：不允许"));
    }

    private static AiExpertPromptBo bo(Long modelId) {
        AiExpertPromptBo bo = new AiExpertPromptBo();
        bo.setModelId(modelId);
        bo.setAgentName("财务专家");
        return bo;
    }

    private static AiModelConfigVo chatModel(String name) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(1L);
        vo.setConfigName(name);
        vo.setModelCode(name);
        vo.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        return vo;
    }

    private static String chatBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\"", "\\\"") + "\"}}]}";
    }

    private static void stubHttp(MockedStatic<HttpUtil> http, int status, String body) {
        HttpRequest request = mock(HttpRequest.class);
        HttpResponse response = mock(HttpResponse.class);
        when(request.header(anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyInt())).thenReturn(request);
        when(request.body(anyString())).thenReturn(request);
        when(request.execute()).thenReturn(response);
        when(response.getStatus()).thenReturn(status);
        when(response.body()).thenReturn(body);
        http.when(() -> HttpUtil.createPost(anyString())).thenReturn(request);
    }

}