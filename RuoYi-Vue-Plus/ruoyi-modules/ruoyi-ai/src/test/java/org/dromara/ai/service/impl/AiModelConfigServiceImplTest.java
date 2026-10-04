package org.dromara.ai.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.domain.AiModelConfig;
import org.dromara.ai.domain.bo.AiModelConfigBo;
import org.dromara.ai.domain.vo.AiModelConfigVo;
import org.dromara.ai.domain.vo.AiModelTestVo;
import org.dromara.ai.mapper.AiKnowledgeBaseMapper;
import org.dromara.ai.mapper.AiModelConfigMapper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiModelConfigServiceImpl} 单元测试：密钥掩码、重名校验、副本生成与连通性测试分支。
 */
@DisplayName("AI 模型配置服务")
class AiModelConfigServiceImplTest {

    private final AiModelConfigMapper baseMapper = mock(AiModelConfigMapper.class);
    private final AiKnowledgeBaseMapper knowledgeBaseMapper = mock(AiKnowledgeBaseMapper.class);
    private final AiSecretManager secretManager = mock(AiSecretManager.class);
    private final AiProperties aiProperties = new AiProperties();
    private final AiModelConfigServiceImpl service =
        new AiModelConfigServiceImpl(baseMapper, knowledgeBaseMapper, secretManager, aiProperties);

    private MockedStatic<MapstructUtils> mapstruct;

    @BeforeAll
    static void initContext() {
        AiServiceTestContext.initialize();
    }

    @BeforeEach
    void setUp() {
        mapstruct = mockStatic(MapstructUtils.class);
        mapstruct.when(() -> MapstructUtils.convert(any(AiModelConfigBo.class), eq(AiModelConfig.class)))
            .thenAnswer(inv -> AiServiceTestContext.copy(inv.getArgument(0), AiModelConfig.class));
    }

    @AfterEach
    void tearDown() {
        mapstruct.close();
    }

    @Test
    @DisplayName("按主键查询计算密钥掩码")
    void shouldMaskApiKeyOnQueryById() {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setId(1L);
        vo.setApiKey("cipher");
        when(baseMapper.selectVoById(1L)).thenReturn(vo);

        AiModelConfigVo result = service.queryById(1L);

        assertEquals("******", result.getApiKeyMask());
        when(baseMapper.selectVoById(2L)).thenReturn(null);
        assertNull(service.queryById(2L));
    }

    @Test
    @DisplayName("分页查询逐行计算密钥掩码")
    void shouldMaskApiKeyOnPageList() {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setApiKey("cipher");
        Page<AiModelConfigVo> page = new Page<>(1, 10);
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(baseMapper.selectVoPage(any(), any())).thenReturn(page);

        PageResult<AiModelConfigVo> result = service.queryPageList(new AiModelConfigBo(), new PageQuery(10, 1));

        assertEquals(1L, result.getTotal());
        assertEquals("******", result.getRows().iterator().next().getApiKeyMask());
    }

    @Test
    @DisplayName("查询模型选项时计算密钥掩码")
    void shouldMaskApiKeyOnOptions() {
        AiModelConfigVo vo = new AiModelConfigVo();
        vo.setApiKey("cipher");
        when(baseMapper.selectVoList(any())).thenReturn(List.of(vo));

        assertEquals("******", service.queryOptions("chat").get(0).getApiKeyMask());
    }

    @Test
    @DisplayName("新增模型配置成功时加密密钥")
    void shouldInsertSuccessfully() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setConfigName("gpt");
        bo.setModelType("chat");
        bo.setApiKey("sk-1");
        when(secretManager.encrypt("sk-1")).thenReturn("cipher");
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(AiModelConfig.class))).thenReturn(1);

        assertTrue(service.insertByBo(bo));
        verify(secretManager).encrypt("sk-1");
    }

    @Test
    @DisplayName("向量模型缺少维度时拒绝保存")
    void shouldRejectEmbeddingWithoutDimension() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setConfigName("emb");
        bo.setModelType("embedding");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals("向量模型必须填写向量维度", ex.getMessage());
    }

    @Test
    @DisplayName("配置名称重复时拒绝保存")
    void shouldRejectDuplicateName() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setConfigName("gpt");
        bo.setModelType("chat");
        when(baseMapper.selectCount(any())).thenReturn(1L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        assertEquals("配置名称已存在：gpt", ex.getMessage());
    }

    @Test
    @DisplayName("复制时源配置不存在抛出异常")
    void shouldRejectCopyWhenSourceMissing() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.copyById(1L));
    }

    @Test
    @DisplayName("复制配置成功生成副本名称")
    void shouldCopySuccessfully() {
        AiModelConfig source = new AiModelConfig();
        source.setId(1L);
        source.setConfigName("gpt");
        source.setModelType("chat");
        when(baseMapper.selectById(1L)).thenReturn(source);
        when(baseMapper.selectCount(any())).thenReturn(0L);
        AiModelConfigVo copied = new AiModelConfigVo();
        when(baseMapper.selectVoById(any())).thenReturn(copied);

        assertSame(copied, service.copyById(1L));
        verify(baseMapper).insert(any(AiModelConfig.class));
    }

    @Test
    @DisplayName("副本名称冲突重试耗尽抛出异常")
    void shouldThrowWhenCopyNameAlwaysConflicts() {
        AiModelConfig source = new AiModelConfig();
        source.setId(1L);
        source.setConfigName("gpt");
        source.setModelType("chat");
        when(baseMapper.selectById(1L)).thenReturn(source);
        when(baseMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("dup")).when(baseMapper).insert(any(AiModelConfig.class));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.copyById(1L));
        assertEquals("副本名称冲突，请稍后重试", ex.getMessage());
    }

    @Test
    @DisplayName("副本名称超长时拒绝追加后缀")
    void shouldRejectCopyWhenNameTooLong() {
        AiModelConfig source = new AiModelConfig();
        source.setId(1L);
        source.setConfigName("a".repeat(100));
        source.setModelType("chat");
        when(baseMapper.selectById(1L)).thenReturn(source);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.copyById(1L));
        assertTrue(ex.getMessage().contains("字符上限"));
    }

    @Test
    @DisplayName("修改模型配置成功")
    void shouldUpdateSuccessfully() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setId(1L);
        bo.setConfigName("gpt");
        bo.setModelType("chat");
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.updateById(any(AiModelConfig.class))).thenReturn(1);

        assertTrue(service.updateByBo(bo));
    }

    @Test
    @DisplayName("删除时主键数量不一致抛出异常")
    void shouldRejectDeleteSizeMismatch() {
        when(baseMapper.selectByIds(any())).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("模型被知识库引用为向量模型时拒绝删除")
    void shouldRejectDeleteWhenReferenced() {
        AiModelConfig config = new AiModelConfig();
        when(baseMapper.selectByIds(any())).thenReturn(List.of(config));
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(1L);

        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("删除模型配置成功")
    void shouldDeleteSuccessfully() {
        AiModelConfig config = new AiModelConfig();
        when(baseMapper.selectByIds(any())).thenReturn(List.of(config));
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.deleteByIds(any())).thenReturn(1);

        assertTrue(service.deleteWithValidByIds(List.of(1L), Boolean.TRUE));
    }

    @Test
    @DisplayName("解密密钥：配置不存在返回空")
    void shouldDecryptApiKey() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertNull(service.decryptApiKey(1L));

        AiModelConfig config = new AiModelConfig();
        config.setApiKey("cipher");
        when(baseMapper.selectById(2L)).thenReturn(config);
        when(secretManager.decrypt("cipher")).thenReturn("sk-1");
        assertEquals("sk-1", service.decryptApiKey(2L));
    }

    @Test
    @DisplayName("服务地址为空时连通性测试失败")
    void shouldFailTestConnectionWhenUrlBlank() {
        AiModelTestVo result = service.testConnection(new AiModelConfigBo());

        assertFalse(result.getSuccess());
        assertEquals("服务地址不能为空", result.getMessage());
    }

    @Test
    @DisplayName("服务地址含占位符时连通性测试失败")
    void shouldFailTestConnectionWhenUrlHasPlaceholder() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setBaseUrl("https://x/{WorkspaceId}");

        AiModelTestVo result = service.testConnection(bo);

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("占位符"));
    }

    @Test
    @DisplayName("服务地址缺少协议时连通性测试失败")
    void shouldFailTestConnectionWhenUrlWithoutScheme() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setBaseUrl("api.openai.com/v1");

        AiModelTestVo result = service.testConnection(bo);

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("http://"));
    }

    @Test
    @DisplayName("DashScope 原生接口地址时连通性测试失败并提示兼容模式")
    void shouldFailTestConnectionForDashScopeNative() {
        AiModelConfigBo bo = new AiModelConfigBo();
        bo.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");

        AiModelTestVo result = service.testConnection(bo);

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("compatible-mode"));
    }

    @Test
    @DisplayName("对话模型连通性测试成功解析回复")
    void shouldTestChatConnectionSuccessfully() {
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            stubHttp(http, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            AiModelConfigBo bo = new AiModelConfigBo();
            bo.setBaseUrl("https://api.openai.com/v1");
            bo.setModelType("chat");
            bo.setModelCode("gpt-4o");
            bo.setApiKey("sk-1");

            AiModelTestVo result = service.testConnection(bo);

            assertTrue(result.getSuccess());
            assertTrue(result.getMessage().contains("ok"));
        }
    }

    @Test
    @DisplayName("向量模型连通性测试返回维度不一致提示")
    void shouldTestEmbeddingConnectionDimensionMismatch() {
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            stubHttp(http, 200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}");
            AiModelConfigBo bo = new AiModelConfigBo();
            bo.setBaseUrl("https://api.openai.com/v1");
            bo.setModelType("embedding");
            bo.setModelCode("text-embedding-3");
            bo.setDimension(1024);

            AiModelTestVo result = service.testConnection(bo);

            assertTrue(result.getSuccess());
            assertTrue(result.getMessage().contains("不一致"));
        }
    }

    @Test
    @DisplayName("非 2xx 响应时连通性测试失败")
    void shouldFailTestConnectionOnNon2xx() {
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            stubHttp(http, 500, "");
            AiModelConfigBo bo = new AiModelConfigBo();
            bo.setBaseUrl("https://api.openai.com/v1");
            bo.setModelType("chat");
            bo.setModelCode("gpt-4o");

            AiModelTestVo result = service.testConnection(bo);

            assertFalse(result.getSuccess());
            assertTrue(result.getMessage().contains("HTTP 500"));
        }
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