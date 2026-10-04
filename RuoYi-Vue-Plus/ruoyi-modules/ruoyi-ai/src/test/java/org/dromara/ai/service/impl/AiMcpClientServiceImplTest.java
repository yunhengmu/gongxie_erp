package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.domain.AiMcpClient;
import org.dromara.ai.domain.AiScenarioPackMcpTool;
import org.dromara.ai.domain.bo.AiMcpClientBo;
import org.dromara.ai.domain.dto.McpClientAssembly;
import org.dromara.ai.domain.vo.AiMcpClientTestVo;
import org.dromara.ai.domain.vo.AiMcpClientVo;
import org.dromara.ai.mapper.AiMcpClientMapper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiMcpClientServiceImpl} 单元测试：配置查询、保存校验与按场景包装配。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("外部 MCP 连接 Service")
class AiMcpClientServiceImplTest {

    @Mock
    private AiMcpClientMapper baseMapper;
    @Mock
    private AiSecretManager secretManager;

    @InjectMocks
    private AiMcpClientServiceImpl service;

    @BeforeAll
    static void setUp() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiMcpClient.class);
    }

    @Test
    @DisplayName("主键查询：空实体返回 null，命中回填请求头标识")
    void shouldQueryById() {
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertNull(service.queryById(1L));

        AiMcpClient entity = new AiMcpClient();
        entity.setId(2L);
        entity.setHeadersJson("cipher");
        when(baseMapper.selectById(2L)).thenReturn(entity);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            AiMcpClientVo vo = new AiMcpClientVo();
            ms.when(() -> MapstructUtils.convert(entity, AiMcpClientVo.class)).thenReturn(vo);
            assertTrue(service.queryById(2L).isHeadersConfigured());
        }
    }

    @Test
    @DisplayName("分页查询逐条转换")
    void shouldQueryPageList() {
        AiMcpClient first = new AiMcpClient();
        first.setId(1L);
        first.setHeadersJson("cipher");
        Page<AiMcpClient> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(first));
        doReturn(page).when(baseMapper).selectPage(any(), any());

        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiMcpClient.class), eq(AiMcpClientVo.class)))
                .thenReturn(new AiMcpClientVo());
            PageResult<AiMcpClientVo> result = service.queryPageList(new AiMcpClientBo(), new PageQuery(10, 1));
            assertEquals(1, result.getTotal());
            assertEquals(1, result.getRows().size());
        }
    }

    @Test
    @DisplayName("新增：规范化传输方式并加密请求头")
    void shouldInsert() {
        AiMcpClientBo bo = new AiMcpClientBo();
        bo.setServerName(" srv ");
        bo.setTransport("streamable-http");
        bo.setUrl(" http://x ");
        bo.setHeadersJson("{\"a\":\"b\"}");

        AiMcpClient entity = new AiMcpClient();
        entity.setId(100L);
        entity.setTransport("streamable-http");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiMcpClientBo.class), eq(AiMcpClient.class))).thenReturn(entity);
            when(secretManager.encrypt(any())).thenReturn("cipher");
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiMcpClient.class))).thenReturn(1);
            assertTrue(service.insertByBo(bo));
        }
        assertEquals(AiMcpClient.TRANSPORT_HTTP, entity.getTransport());
    }

    @Test
    @DisplayName("新增：非法传输方式或重名时拒绝")
    void shouldRejectInvalidInsert() {
        AiMcpClientBo bo = new AiMcpClientBo();
        bo.setTransport("stdio");
        AiMcpClient invalidTransport = new AiMcpClient();
        invalidTransport.setTransport("stdio");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiMcpClientBo.class), eq(AiMcpClient.class)))
                .thenReturn(invalidTransport);
            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }

        AiMcpClientBo dup = new AiMcpClientBo();
        dup.setServerName("srv");
        AiMcpClient dupEntity = new AiMcpClient();
        dupEntity.setTransport("sse");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiMcpClientBo.class), eq(AiMcpClient.class)))
                .thenReturn(dupEntity);
            when(baseMapper.selectCount(any())).thenReturn(1L);
            assertThrows(ServiceException.class, () -> service.insertByBo(dup));
        }
    }

    @Test
    @DisplayName("修改：不存在拒绝，留空请求头保持原值")
    void shouldUpdate() {
        AiMcpClientBo bo = new AiMcpClientBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));

        AiMcpClient exists = new AiMcpClient();
        exists.setHeadersJson("old-cipher");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiMcpClient update = new AiMcpClient();
        update.setId(1L);
        update.setTransport("sse");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiMcpClientBo.class), eq(AiMcpClient.class))).thenReturn(update);
            when(baseMapper.updateById(any(AiMcpClient.class))).thenReturn(1);
            assertTrue(service.updateByBo(bo));
        }
        assertEquals("old-cipher", update.getHeadersJson());
    }

    @Test
    @DisplayName("删除：空参拒绝，正常删除")
    void shouldDelete() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(), false));
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteWithValidByIds(List.of(1L), false));
    }

    @Test
    @DisplayName("测试连接：必填缺失直接返回失败")
    void shouldFailTestConnectionWhenBlank() {
        AiMcpClientBo bo = new AiMcpClientBo();
        bo.setTransport("sse");
        AiMcpClientTestVo vo = service.testConnection(bo);

        assertFalse(vo.isSuccess());
        assertEquals("请先填写 Server 名称、传输方式与地址", vo.getMessage());
        assertTrue(vo.getTools().isEmpty());
    }

    @Test
    @DisplayName("装配：空选择或无效选择返回空结果")
    void shouldReturnEmptyAssemblyForBlankSelections() {
        assertTrue(service.resolveForAssembly(null).serverNames().isEmpty());
        assertTrue(service.resolveForAssembly(List.of()).serverNames().isEmpty());

        AiScenarioPackMcpTool invalid = new AiScenarioPackMcpTool();
        assertTrue(service.resolveForAssembly(List.of(invalid)).serverNames().isEmpty());
    }

    @Test
    @DisplayName("装配：选中连接已停用时忽略")
    void shouldIgnoreDisabledClients() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        AiScenarioPackMcpTool selection = selection(9L, "weather.search", "0");

        McpClientAssembly assembly = service.resolveForAssembly(List.of(selection));

        assertTrue(assembly.serverNames().isEmpty());
    }

    @Test
    @DisplayName("装配：白名单/发现结果过滤后生成归一化工具名")
    void shouldResolveAssembly() throws Exception {
        AiMcpClient client = enabledClient(1L, "srv", "weather.search");
        AiMcpClient other = enabledClient(2L, "srv2", "other");
        when(baseMapper.selectList(any())).thenReturn(List.of(client, other));
        seedDiscovery(1L, List.of("weather.search"));

        McpClientAssembly assembly = service.resolveForAssembly(
            List.of(selection(1L, "weather.search", "0")));

        assertEquals(List.of("srv"), assembly.serverNames());
        assertTrue(assembly.toolNames().contains("weather_search"));
        assertEquals("weather_search", assembly.aliasesOf("srv").get("weather.search"));
        assertEquals("0", assembly.decisionOf("weather_search"));
        assertFalse(assembly.signature().isEmpty());
    }

    @Test
    @DisplayName("装配：已合法工具名不产生改名映射")
    void shouldKeepLegalToolName() throws Exception {
        AiMcpClient client = enabledClient(1L, "srv", "plain");
        when(baseMapper.selectList(any())).thenReturn(List.of(client));
        seedDiscovery(1L, List.of("plain"));

        McpClientAssembly assembly = service.resolveForAssembly(List.of(selection(1L, "plain", "1")));

        assertTrue(assembly.toolNames().contains("plain"));
        assertTrue(assembly.aliasesOf("srv").isEmpty());
    }

    @Test
    @DisplayName("装配：选中工具未被发现时忽略连接")
    void shouldIgnoreMissingTool() throws Exception {
        AiMcpClient client = enabledClient(1L, "srv", "known");
        when(baseMapper.selectList(any())).thenReturn(List.of(client));
        seedDiscovery(1L, List.of("known"));

        McpClientAssembly assembly = service.resolveForAssembly(List.of(selection(1L, "unknown", "0")));

        assertTrue(assembly.serverNames().isEmpty());
    }

    @Test
    @DisplayName("打开客户端：空装配或连接不存在时为空")
    void shouldOpenClients() {
        assertTrue(service.openClients(McpClientAssembly.empty()).isEmpty());

        when(baseMapper.selectList(any())).thenReturn(List.of());
        McpClientAssembly assembly = new McpClientAssembly(List.of("nope"), List.of(), java.util.Map.of(),
            java.util.Map.of(), "");
        assertTrue(service.openClients(assembly).isEmpty());
    }

    private static AiMcpClient enabledClient(Long id, String serverName, String enableTools) {
        AiMcpClient client = new AiMcpClient();
        client.setId(id);
        client.setServerName(serverName);
        client.setTransport(AiMcpClient.TRANSPORT_SSE);
        client.setUrl("http://localhost/" + id);
        client.setEnableTools(enableTools);
        client.setStatus(AiMcpClient.STATUS_ENABLED);
        client.setUpdateTime(LocalDateTime.now());
        return client;
    }

    private static AiScenarioPackMcpTool selection(Long clientId, String toolName, String decision) {
        AiScenarioPackMcpTool selection = new AiScenarioPackMcpTool();
        selection.setMcpClientId(clientId);
        selection.setToolName(toolName);
        selection.setDecision(decision);
        return selection;
    }

    /**
     * 预热工具发现缓存，避免装配测试触发真实外连。
     */
    private void seedDiscovery(Long clientId, List<String> tools) throws Exception {
        Field field = AiMcpClientServiceImpl.class.getDeclaredField("discoveryCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, Object> cache = (ConcurrentHashMap<Long, Object>) field.get(service);
        Class<?> cacheClass = Class.forName("org.dromara.ai.service.impl.AiMcpClientServiceImpl$DiscoveryCache");
        Constructor<?> ctor = cacheClass.getDeclaredConstructor(long.class, List.class);
        ctor.setAccessible(true);
        cache.put(clientId, ctor.newInstance(System.currentTimeMillis(), tools));
    }

}