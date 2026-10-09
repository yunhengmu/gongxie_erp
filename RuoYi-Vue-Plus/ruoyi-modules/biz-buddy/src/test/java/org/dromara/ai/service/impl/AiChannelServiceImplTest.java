package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.linpeilie.Converter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.ai.config.AiSecretManager;
import org.dromara.ai.core.channel.AiChannelStreamStatus;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.domain.AiChannelIdentity;
import org.dromara.ai.domain.AiChannelThread;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiChannelBo;
import org.dromara.ai.domain.bo.AiChannelIdentityBo;
import org.dromara.ai.domain.vo.AiChannelIdentityVo;
import org.dromara.ai.domain.vo.AiChannelThreadVo;
import org.dromara.ai.domain.vo.AiChannelVo;
import org.dromara.ai.mapper.AiChannelIdentityMapper;
import org.dromara.ai.mapper.AiChannelMapper;
import org.dromara.ai.mapper.AiChannelThreadMapper;
import org.dromara.ai.mapper.AiSessionMapper;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiChannelServiceImpl} 单元测试：渠道配置、身份映射与线程映射。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("渠道接入 Service")
class AiChannelServiceImplTest {

    @Mock
    private AiChannelMapper baseMapper;
    @Mock
    private AiChannelIdentityMapper identityMapper;
    @Mock
    private AiChannelThreadMapper threadMapper;
    @Mock
    private AiSessionMapper sessionMapper;
    @Mock
    private IAiAgentService agentService;
    @Mock
    private AiSecretManager secretManager;
    @Mock
    private UserService userService;
    @Spy
    private AiChannelStreamStatus streamStatus = new AiChannelStreamStatus();

    @InjectMocks
    private AiChannelServiceImpl service;

    @BeforeAll
    static void setUp() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
        for (Class<?> clazz : new Class<?>[]{AiChannel.class, AiChannelIdentity.class, AiChannelThread.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
        }
    }

    @Test
    @DisplayName("渠道分页：填充智能体名、凭据标识与长连接状态")
    void shouldQueryChannelPage() {
        AiChannelVo vo = new AiChannelVo();
        vo.setId(1L);
        vo.setAgentId(2L);
        Page<AiChannelVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(baseMapper).selectVoPage(any(), any());

        AiAgent agent = mock(AiAgent.class);
        when(agent.getId()).thenReturn(2L);
        when(agent.getAgentName()).thenReturn("小Z");
        when(agent.getStatus()).thenReturn("0");
        when(agentService.queryEntities(any())).thenReturn(List.of(agent));
        AiChannel raw = new AiChannel();
        raw.setId(1L);
        raw.setPlatformType(AiChannel.PLATFORM_DINGTALK);
        raw.setChannelCode("c1");
        raw.setAppSecret("cipher");
        when(baseMapper.selectByIds(any())).thenReturn(List.of(raw));

        PageResult<AiChannelVo> result = service.queryPageList(new AiChannelBo(), new PageQuery(10, 1));

        AiChannelVo filled = result.getRows().iterator().next();
        assertEquals("小Z", filled.getAgentName());
        assertTrue(filled.getAppSecretSet());
        assertEquals(AiChannelStreamStatus.STOPPED, filled.getStreamStatus());
    }

    @Test
    @DisplayName("渠道详情：缺失抛异常，命中返回")
    void shouldQueryChannelById() {
        when(baseMapper.selectVoById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.queryById(1L));

        AiChannelVo vo = new AiChannelVo();
        vo.setId(2L);
        vo.setAgentId(3L);
        when(baseMapper.selectVoById(2L)).thenReturn(vo);
        when(baseMapper.selectByIds(any())).thenReturn(List.of());
        assertEquals(2L, service.queryById(2L).getId().longValue());
    }

    @Test
    @DisplayName("渠道新增：飞书凭据齐全时成功")
    void shouldInsertChannel() {
        AiChannelBo bo = new AiChannelBo();
        bo.setPlatformType(AiChannel.PLATFORM_FEISHU);
        bo.setAgentId(3L);
        bo.setAppId("app");
        bo.setAppSecret("secret");

        AiChannel entity = new AiChannel();
        entity.setAgentId(3L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelBo.class), eq(AiChannel.class))).thenReturn(entity);
            when(secretManager.encrypt(any())).thenReturn("cipher");
            when(agentService.getEnabledAgent(3L)).thenReturn(mock(AiAgent.class));
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.insert(any(AiChannel.class))).thenReturn(1);
            assertTrue(service.insertByBo(bo));
        }
        assertEquals(AiChannel.STATUS_ENABLED, entity.getStatus());
    }

    @Test
    @DisplayName("渠道新增：平台非法或凭据缺失时拒绝")
    void shouldRejectInvalidChannel() {
        AiChannelBo badPlatform = new AiChannelBo();
        badPlatform.setPlatformType("unknown");
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelBo.class), eq(AiChannel.class)))
                .thenReturn(new AiChannel());
            assertThrows(ServiceException.class, () -> service.insertByBo(badPlatform));
        }

        AiChannelBo missingSecret = new AiChannelBo();
        missingSecret.setPlatformType(AiChannel.PLATFORM_FEISHU);
        missingSecret.setAgentId(3L);
        missingSecret.setAppId("app");
        AiChannel entity = new AiChannel();
        entity.setAgentId(3L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelBo.class), eq(AiChannel.class))).thenReturn(entity);
            when(agentService.getEnabledAgent(3L)).thenReturn(mock(AiAgent.class));
            when(baseMapper.selectCount(any())).thenReturn(0L);
            assertThrows(ServiceException.class, () -> service.insertByBo(missingSecret));
        }
    }

    @Test
    @DisplayName("渠道新增：企业微信 AESKey 长度非法时拒绝")
    void shouldRejectWecomAesKey() {
        AiChannelBo bo = new AiChannelBo();
        bo.setPlatformType(AiChannel.PLATFORM_WECOM);
        bo.setAgentId(3L);
        bo.setAppId("corp");
        bo.setAppSecret("secret");
        bo.setPlatformAgentId("10");
        bo.setVerificationToken("token");
        bo.setEncryptKey("short");
        AiChannel entity = new AiChannel();
        entity.setAgentId(3L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelBo.class), eq(AiChannel.class))).thenReturn(entity);
            when(secretManager.encrypt(any())).thenReturn("cipher");
            when(agentService.getEnabledAgent(3L)).thenReturn(mock(AiAgent.class));
            when(baseMapper.selectCount(any())).thenReturn(0L);
            assertThrows(ServiceException.class, () -> service.insertByBo(bo));
        }
    }

    @Test
    @DisplayName("渠道修改：不存在拒绝，留空凭据保持原值")
    void shouldUpdateChannel() {
        AiChannelBo bo = new AiChannelBo();
        bo.setId(1L);
        when(baseMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.updateByBo(bo));

        AiChannel exists = new AiChannel();
        exists.setAppSecret("old");
        when(baseMapper.selectById(1L)).thenReturn(exists);
        AiChannelBo edit = new AiChannelBo();
        edit.setId(1L);
        edit.setPlatformType(AiChannel.PLATFORM_FEISHU);
        edit.setAgentId(3L);
        edit.setAppId("app");
        AiChannel update = new AiChannel();
        update.setAgentId(3L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelBo.class), eq(AiChannel.class))).thenReturn(update);
            when(agentService.getEnabledAgent(3L)).thenReturn(mock(AiAgent.class));
            when(baseMapper.selectCount(any())).thenReturn(0L);
            when(baseMapper.updateById(any(AiChannel.class))).thenReturn(1);
            assertTrue(service.updateByBo(edit));
        }
        assertEquals("old", update.getAppSecret());
    }

    @Test
    @DisplayName("渠道删除：空参/不存在拒绝，正常级联清理")
    void shouldDeleteChannel() {
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of()));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiChannel()));
        assertThrows(ServiceException.class, () -> service.deleteWithValidByIds(List.of(1L, 2L)));

        when(baseMapper.selectByIds(any())).thenReturn(List.of(new AiChannel(), new AiChannel()));
        when(identityMapper.delete(any())).thenReturn(1);
        when(threadMapper.delete(any())).thenReturn(1);
        when(baseMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteWithValidByIds(List.of(1L, 2L)));
        verify(identityMapper, times(1)).delete(any());
        verify(threadMapper, times(1)).delete(any());
    }

    @Test
    @DisplayName("运行期配置：空编码/未启用返回 null，命中解密凭据")
    void shouldLoadRuntimeConfig() {
        assertNull(service.loadRuntimeConfig(" "));

        when(baseMapper.selectOne(any())).thenReturn(null);
        assertNull(service.loadRuntimeConfig("c1"));

        AiChannel channel = new AiChannel();
        channel.setId(1L);
        channel.setChannelCode("c1");
        channel.setPlatformType(AiChannel.PLATFORM_FEISHU);
        channel.setAppSecret("a");
        channel.setEncryptKey("b");
        channel.setVerificationToken("c");
        channel.setWebhookSecret("d");
        channel.setApiToken("e");
        when(baseMapper.selectOne(any())).thenReturn(channel);
        when(secretManager.decrypt(any())).thenAnswer(invocation -> "plain-" + invocation.getArgument(0));

        AiChannel runtime = service.loadRuntimeConfig("c1");

        assertEquals("plain-a", runtime.getAppSecret());
        assertEquals("plain-e", runtime.getApiToken());
    }

    @Test
    @DisplayName("启用渠道编码：空类型返回空列表")
    void shouldListEnabledChannelCodes() {
        assertTrue(service.listEnabledChannelCodes(" ").isEmpty());

        AiChannel c1 = new AiChannel();
        c1.setChannelCode("c1");
        when(baseMapper.selectList(any())).thenReturn(List.of(c1));
        assertEquals(List.of("c1"), service.listEnabledChannelCodes("feishu"));
    }

    @Test
    @DisplayName("身份映射分页填充渠道名与用户昵称")
    void shouldQueryIdentityPage() {
        AiChannelIdentityVo vo = new AiChannelIdentityVo();
        vo.setId(1L);
        vo.setChannelId(1L);
        vo.setPlatformUserId(2L);
        Page<AiChannelIdentityVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(identityMapper).selectVoPage(any(), any());

        AiChannel channel = new AiChannel();
        channel.setId(1L);
        channel.setChannelName("渠道A");
        when(baseMapper.selectByIds(any())).thenReturn(List.of(channel));
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(2L, "李四"));

        AiChannelIdentityVo filled = service.queryIdentityPageList(new AiChannelIdentityBo(),
            new PageQuery(10, 1)).getRows().iterator().next();
        assertEquals("渠道A", filled.getChannelName());
        assertEquals("李四", filled.getPlatformUserName());
    }

    @Test
    @DisplayName("身份新增/修改：校验渠道、唯一与平台用户")
    void shouldManageIdentity() {
        AiChannel channel = new AiChannel();
        when(baseMapper.selectById(1L)).thenReturn(channel);
        when(identityMapper.selectCount(any())).thenReturn(0L);
        when(userService.selectById(2L)).thenReturn(new UserDTO());
        when(identityMapper.insert(any(AiChannelIdentity.class))).thenReturn(1);

        AiChannelIdentityBo bo = new AiChannelIdentityBo();
        bo.setChannelId(1L);
        bo.setExternalUserId("open_id");
        bo.setPlatformUserId(2L);
        AiChannelIdentity add = new AiChannelIdentity();
        add.setChannelId(1L);
        add.setPlatformUserId(2L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelIdentityBo.class), eq(AiChannelIdentity.class)))
                .thenReturn(add);
            assertTrue(service.insertIdentity(bo));
        }

        when(identityMapper.selectById(5L)).thenReturn(null);
        AiChannelIdentityBo edit = new AiChannelIdentityBo();
        edit.setId(5L);
        assertThrows(ServiceException.class, () -> service.updateIdentity(edit));

        AiChannelIdentity exists = new AiChannelIdentity();
        when(identityMapper.selectById(5L)).thenReturn(exists);
        AiChannelIdentity update = new AiChannelIdentity();
        update.setChannelId(1L);
        update.setPlatformUserId(2L);
        try (MockedStatic<MapstructUtils> ms = mockStatic(MapstructUtils.class)) {
            ms.when(() -> MapstructUtils.convert(any(AiChannelIdentityBo.class), eq(AiChannelIdentity.class)))
                .thenReturn(update);
            when(identityMapper.updateById(any(AiChannelIdentity.class))).thenReturn(1);
            assertTrue(service.updateIdentity(edit));
        }
    }

    @Test
    @DisplayName("身份解析与删除")
    void shouldResolveIdentityAndDelete() {
        assertNull(service.resolvePlatformUserId(null, "open"));
        assertNull(service.resolvePlatformUserId(1L, " "));

        when(identityMapper.selectOne(any())).thenReturn(null);
        assertNull(service.resolvePlatformUserId(1L, "open"));

        AiChannelIdentity identity = new AiChannelIdentity();
        identity.setPlatformUserId(8L);
        when(identityMapper.selectOne(any())).thenReturn(identity);
        assertEquals(8L, service.resolvePlatformUserId(1L, "open").longValue());

        assertThrows(ServiceException.class, () -> service.deleteIdentities(List.of()));
        when(identityMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteIdentities(List.of(1L)));
    }

    @Test
    @DisplayName("线程映射：分页填充、查找、绑定与触碰")
    void shouldManageThreads() {
        AiChannelThreadVo vo = new AiChannelThreadVo();
        vo.setId(1L);
        vo.setChannelId(1L);
        vo.setSessionId(2L);
        Page<AiChannelThreadVo> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(vo));
        doReturn(page).when(threadMapper).selectVoPage(any(), any());

        AiChannel channel = new AiChannel();
        channel.setId(1L);
        channel.setChannelName("渠道A");
        when(baseMapper.selectByIds(any())).thenReturn(List.of(channel));
        AiSession session = new AiSession();
        session.setId(2L);
        session.setSessionTitle("会话标题");
        session.setStatus("0");
        when(sessionMapper.selectByIds(any())).thenReturn(List.of(session));

        AiChannelThreadVo filled = service.queryThreadPageList(1L, new PageQuery(10, 1))
            .getRows().iterator().next();
        assertEquals("渠道A", filled.getChannelName());
        assertEquals("会话标题", filled.getSessionTitle());

        AiChannelThread thread = new AiChannelThread();
        when(threadMapper.selectOne(any())).thenReturn(thread);
        assertNotNull(service.findThread(1L, "DIRECT:1"));

        service.bindThread(1L, "DIRECT:1", 2L);
        verify(threadMapper, times(1)).insert(any(AiChannelThread.class));

        service.touchThread(1L, "DIRECT:1");
        verify(threadMapper, times(1)).update(any(), any());

        assertThrows(ServiceException.class, () -> service.deleteThreads(List.of()));
        when(threadMapper.deleteByIds(any())).thenReturn(1);
        assertTrue(service.deleteThreads(List.of(1L)));
    }

}