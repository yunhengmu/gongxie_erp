package org.dromara.ai.core.agent;

import org.dromara.ai.domain.AiEntryAgent;
import org.dromara.ai.domain.AiExpertAgent;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiModelConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link AiVisionGuard} 单元测试：图片附件的「支持视觉」能力校验。
 *
 * <p>覆盖：入口智能体模型不支持 → 拒绝；指定专家模型不支持 → 拒绝；两者都不支持 → 文案含两条；
 * 已声明支持 / 存量空值 / 模型缺失 / 会话不存在 → 放行。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("图片视觉能力声明校验")
class AiVisionGuardTest {

    private static final long SESSION_ID = 100L;

    @Mock
    private AiSessionMapper sessionMapper;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private IAiModelConfigService modelConfigService;

    @InjectMocks
    private AiVisionGuard guard;

    private static AiSession session(Long agentId, Long pinnedExpertId) {
        AiSession session = new AiSession();
        session.setId(SESSION_ID);
        session.setAgentId(agentId);
        session.setPinnedExpertId(pinnedExpertId);
        return session;
    }

    private static AiEntryAgent entry(Long modelId) {
        AiEntryAgent agent = new AiEntryAgent();
        agent.setId(1L);
        agent.setAgentName("小Z");
        agent.setModelId(modelId);
        return agent;
    }

    private static AiExpertAgent expert(Long id, String name, Long modelId) {
        AiExpertAgent agent = new AiExpertAgent();
        agent.setId(id);
        agent.setAgentName(name);
        agent.setModelId(modelId);
        return agent;
    }

    private static AiModelConfigVo model(String configName, String supportsVision) {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setConfigName(configName);
        vo.setSupportsVision(supportsVision);
        return vo;
    }

    @Test
    @DisplayName("会话不存在时放行")
    void shouldPassWhenSessionMissing() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(null);

        assertDoesNotThrow(() -> guard.assertImageSupported(SESSION_ID));
    }

    @Test
    @DisplayName("入口智能体模型显式不支持视觉时拒绝")
    void shouldRejectWhenEntryModelUnsupported() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session(1L, null));
        when(agentService.queryEntity(1L)).thenReturn(entry(9L));
        when(modelConfigService.queryById(9L)).thenReturn(model("纯文本模型", AiModelConfig.VISION_UNSUPPORTED));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> guard.assertImageSupported(SESSION_ID));

        assertTrue(ex.getMessage().contains("支持视觉"), ex.getMessage());
        assertTrue(ex.getMessage().contains("「小Z」的模型「纯文本模型」"), ex.getMessage());
    }

    @Test
    @DisplayName("指定专家模型不支持视觉时拒绝，文案标注指定专家")
    void shouldRejectWhenPinnedExpertModelUnsupported() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session(1L, 2L));
        when(agentService.queryEntity(1L)).thenReturn(entry(9L));
        when(modelConfigService.queryById(9L)).thenReturn(model("视觉模型", AiModelConfig.VISION_SUPPORTED));
        when(agentService.queryEntity(2L)).thenReturn(expert(2L, "公告管理专家", 10L));
        when(modelConfigService.queryById(10L)).thenReturn(model("qwen-plus", AiModelConfig.VISION_UNSUPPORTED));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> guard.assertImageSupported(SESSION_ID));

        assertTrue(ex.getMessage().contains("指定专家「公告管理专家」的模型「qwen-plus」"), ex.getMessage());
    }

    @Test
    @DisplayName("入口与指定专家都不支持时，文案同时列出两者")
    void shouldListBothOffenders() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session(1L, 2L));
        when(agentService.queryEntity(1L)).thenReturn(entry(9L));
        when(modelConfigService.queryById(9L)).thenReturn(model("纯文本模型", AiModelConfig.VISION_UNSUPPORTED));
        when(agentService.queryEntity(2L)).thenReturn(expert(2L, "公告管理专家", 10L));
        when(modelConfigService.queryById(10L)).thenReturn(model("qwen-plus", AiModelConfig.VISION_UNSUPPORTED));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> guard.assertImageSupported(SESSION_ID));

        assertEquals("图片需要「支持视觉」的对话模型，但以下模型未声明该能力："
            + "「小Z」的模型「纯文本模型」；指定专家「公告管理专家」的模型「qwen-plus」。"
            + "请在「模型配置」中为其勾选「支持视觉」，或改用视觉模型（如 qwen-vl 系列）后再上传图片", ex.getMessage());
    }

    @Test
    @DisplayName("存量空值与模型缺失一律放行，避免误挡")
    void shouldPassWhenUnsetOrModelMissing() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session(1L, 2L));
        when(agentService.queryEntity(1L)).thenReturn(entry(9L));
        when(modelConfigService.queryById(9L)).thenReturn(model("存量模型", null));
        when(agentService.queryEntity(2L)).thenReturn(expert(2L, "无模型专家", null));

        assertDoesNotThrow(() -> guard.assertImageSupported(SESSION_ID));
    }
}