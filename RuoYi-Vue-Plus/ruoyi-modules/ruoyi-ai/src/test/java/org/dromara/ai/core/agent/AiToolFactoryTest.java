package org.dromara.ai.core.agent;

import io.agentscope.core.tool.AgentTool;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.domain.AiTool;
import org.dromara.ai.service.IAiKnowledgeBaseService;
import org.dromara.ai.service.IAiVectorService;
import org.dromara.common.core.service.PermissionService;
import org.dromara.ai.test.AiTestSupport;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.MenuService;
import org.dromara.system.api.NoticeService;
import org.dromara.system.api.RoleService;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AiToolFactory} 单元测试：实现标识登记判定与工具实例构造。
 */
@DisplayName("业务工具工厂")
class AiToolFactoryTest {

    private final AiToolFactory factory = new AiToolFactory(
        mock(IAiVectorService.class), mock(IAiKnowledgeBaseService.class), mock(NoticeService.class),
        mock(PermissionService.class), new AiProperties(), mock(DeptService.class), mock(UserService.class),
        mock(RoleService.class), mock(MenuService.class));

    @BeforeAll
    static void init() {
        AiTestSupport.initialize();
    }

    private static AiTool tool(String implName) {
        AiTool tool = new AiTool();
        tool.setImplName(implName);
        tool.setToolCode("code-" + implName);
        return tool;
    }

    @Test
    @DisplayName("判定实现标识是否登记")
    void shouldJudgeSupportedImplName() {
        assertTrue(factory.isSupported(AiToolFactory.IMPL_KNOWLEDGE_SEARCH));
        assertTrue(factory.isSupported(AiToolFactory.IMPL_CREATE_USER));
        assertTrue(factory.isSupported(AiToolFactory.IMPL_GET_CURRENT_TIME));
        assertFalse(factory.isSupported("unknown"));
        assertFalse(factory.isSupported(null));
    }

    @Test
    @DisplayName("为全部已登记实现构造工具实例")
    void shouldCreateToolForEveryRegisteredImpl() {
        String[] impls = {
            AiToolFactory.IMPL_KNOWLEDGE_SEARCH, AiToolFactory.IMPL_CREATE_NOTICE, AiToolFactory.IMPL_SEARCH_NOTICE,
            AiToolFactory.IMPL_GET_NOTICE, AiToolFactory.IMPL_UPDATE_NOTICE, AiToolFactory.IMPL_DELETE_NOTICE,
            AiToolFactory.IMPL_GET_CURRENT_TIME, AiToolFactory.IMPL_GET_DEPT, AiToolFactory.IMPL_SEARCH_DEPT,
            AiToolFactory.IMPL_CREATE_DEPT, AiToolFactory.IMPL_UPDATE_DEPT, AiToolFactory.IMPL_DELETE_DEPT,
            AiToolFactory.IMPL_GET_CURRENT_USER, AiToolFactory.IMPL_GET_CURRENT_DEPT,
            AiToolFactory.IMPL_GET_CURRENT_PERMISSIONS, AiToolFactory.IMPL_GET_USER, AiToolFactory.IMPL_SEARCH_USER,
            AiToolFactory.IMPL_CREATE_USER, AiToolFactory.IMPL_UPDATE_USER, AiToolFactory.IMPL_DELETE_USER,
            AiToolFactory.IMPL_SEARCH_ROLES, AiToolFactory.IMPL_GET_ROLE, AiToolFactory.IMPL_SEARCH_MENUS,
            AiToolFactory.IMPL_GET_MENU, AiToolFactory.IMPL_GET_ROLE_PERMISSIONS
        };
        for (String impl : impls) {
            Optional<AgentTool> created = factory.create(tool(impl), List.of(1L));
            assertTrue(created.isPresent(), "应能构造工具：" + impl);
        }
    }

    @Test
    @DisplayName("平台出口场景构造知识库检索工具")
    void shouldCreateKnowledgeSearchWithPlatformScope() {
        Optional<AgentTool> created = factory.create(tool(AiToolFactory.IMPL_KNOWLEDGE_SEARCH), List.of(), true);
        assertTrue(created.isPresent());
    }

    @Test
    @DisplayName("未登记实现返回空并跳过装配")
    void shouldReturnEmptyForUnsupportedImpl() {
        assertTrue(factory.create(tool("nope"), List.of()).isEmpty());
        assertTrue(factory.create(tool(null), List.of()).isEmpty());
    }
}