package org.dromara.ai.service.impl;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiWorkspaceConfigBo;
import org.dromara.ai.domain.bo.AiWorkspaceContentBo;
import org.dromara.ai.domain.vo.AiWorkspaceContentVo;
import org.dromara.ai.domain.vo.AiWorkspaceFileVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AiWorkspaceServiceImpl} 单元测试：受管文件清单、两层读取、路径/层校验、用户覆盖写入与清除。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI 工作区文件 Service")
class AiWorkspaceServiceImplTest {

    @Mock
    private IAiAgentService agentService;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    @InjectMocks
    private AiWorkspaceServiceImpl service;

    private AiAgent definition() {
        AiAgent agent = new AiAgent() {
        };
        agent.setId(1L);
        agent.setAgentCode("code");
        return agent;
    }

    /**
     * 装配「智能体定义 → HarnessAgent → WorkspaceManager」链路。
     */
    private void wire(WorkspaceManager workspace) {
        when(agentService.getEnabledAgent(1L)).thenReturn(definition());
        HarnessAgent harness = mock(HarnessAgent.class);
        when(agentRegistry.get(any(AiAgent.class))).thenReturn(harness);
        when(harness.getWorkspaceManager()).thenReturn(workspace);
    }

    /**
     * 返回一个不存在的根目录，避免共享层真实读写。
     */
    private Path absentRoot() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "bb-ws-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("文件清单：AGENTS.md 为用户覆盖生效")
    void shouldListFilesWithUserOverride() {
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getWorkspace()).thenReturn(absentRoot());
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        when(workspace.getFilesystem()).thenReturn(fs);
        when(fs.read(any(), eq("AGENTS.md"), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(new FileData("用户覆盖", "utf-8")));
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        wire(workspace);

        List<AiWorkspaceFileVo> files = service.listFiles(1L, 9L);

        assertEquals(1, files.size());
        AiWorkspaceFileVo vo = files.get(0);
        assertEquals("AGENTS.md", vo.getPath());
        assertEquals("AGENTS", vo.getType());
        assertTrue(vo.isHasUser());
        assertEquals("USER", vo.getEffective());
    }

    @Test
    @DisplayName("文件清单：附加上下文文件过滤非法路径并去重")
    void shouldFilterAdditionalContextFiles() {
        AiWorkspaceConfigBo config = new AiWorkspaceConfigBo();
        config.setAdditionalContextFiles(List.of("notes.md", "bad.txt", "../evil.md", " "));
        when(runtimeConfigStore.workspace()).thenReturn(config);
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getWorkspace()).thenReturn(absentRoot());
        when(workspace.getFilesystem()).thenReturn(null);
        wire(workspace);

        List<AiWorkspaceFileVo> files = service.listFiles(1L, 9L);

        assertEquals(2, files.size());
        assertEquals("AGENTS.md", files.get(0).getPath());
        assertEquals("notes.md", files.get(1).getPath());
        assertEquals("EXTRA", files.get(1).getType());
        assertEquals("NONE", files.get(1).getEffective());
    }

    @Test
    @DisplayName("读取内容：非法路径抛异常")
    void shouldRejectIllegalPath() {
        assertThrows(ServiceException.class, () -> service.readContent(1L, 9L, "evil", "SHARED"));
    }

    @Test
    @DisplayName("读取内容：不在受管清单内抛异常")
    void shouldRejectUnmanagedPath() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        wire(mock(WorkspaceManager.class));

        assertThrows(ServiceException.class, () -> service.readContent(1L, 9L, "other.md", "SHARED"));
    }

    @Test
    @DisplayName("读取内容：非法写入层抛异常")
    void shouldRejectIllegalScope() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        wire(mock(WorkspaceManager.class));

        assertThrows(ServiceException.class, () -> service.readContent(1L, 9L, "AGENTS.md", "BOTH"));
    }

    @Test
    @DisplayName("读取内容：用户覆盖层返回文件正文")
    void shouldReadUserLayerContent() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        when(workspace.getFilesystem()).thenReturn(fs);
        when(fs.read(any(), eq("AGENTS.md"), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(new FileData("正文", "utf-8")));
        wire(workspace);

        AiWorkspaceContentVo vo = service.readContent(1L, 9L, "AGENTS.md", "USER");

        assertEquals("USER", vo.getScope());
        assertEquals("正文", vo.getContent());
    }

    @Test
    @DisplayName("读取内容：共享层文件缺失返回空串")
    void shouldReadSharedLayerEmpty() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getWorkspace()).thenReturn(absentRoot());
        wire(workspace);

        AiWorkspaceContentVo vo = service.readContent(1L, 9L, "AGENTS.md", "SHARED");

        assertEquals("SHARED", vo.getScope());
        assertEquals("", vo.getContent());
    }

    @Test
    @DisplayName("写入内容：用户覆盖层走工作区写入接口")
    void shouldWriteUserLayerContent() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        wire(workspace);
        AiWorkspaceContentBo bo = contentBo("AGENTS.md", "USER", "内容");

        service.writeContent(bo);

        verify(workspace).writeUtf8WorkspaceRelative(any(), eq("AGENTS.md"), eq("内容"));
    }

    @Test
    @DisplayName("写入内容：共享默认层原子覆盖本地文件")
    void shouldWriteSharedLayerContent() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        Path root = Paths.get("ws-root");
        when(workspace.getWorkspace()).thenReturn(root);
        wire(workspace);
        AiWorkspaceContentBo bo = contentBo("AGENTS.md", "SHARED", "内容");

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            service.writeContent(bo);

            files.verify(() -> Files.createDirectories(any(Path.class)));
        }
    }

    @Test
    @DisplayName("清除用户覆盖：文件不存在时安全返回")
    void shouldResetUserOverride() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.resolveRuntimeDataPath(any(), eq("AGENTS.md"))).thenReturn(absentRoot().resolve("AGENTS.md"));
        wire(workspace);

        assertDoesNotThrow(() -> service.resetUserOverride(1L, 9L, "AGENTS.md"));
    }

    @Test
    @DisplayName("清除用户覆盖：删除异常包装为业务异常")
    void shouldWrapResetError() {
        when(runtimeConfigStore.workspace()).thenReturn(new AiWorkspaceConfigBo());
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.resolveRuntimeDataPath(any(), eq("AGENTS.md"))).thenReturn(absentRoot().resolve("AGENTS.md"));
        wire(workspace);

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(new IOException("io"));

            assertThrows(ServiceException.class, () -> service.resetUserOverride(1L, 9L, "AGENTS.md"));
        }
    }

    private AiWorkspaceContentBo contentBo(String path, String scope, String content) {
        AiWorkspaceContentBo bo = new AiWorkspaceContentBo();
        bo.setAgentId(1L);
        bo.setUserId(9L);
        bo.setPath(path);
        bo.setScope(scope);
        bo.setContent(content);
        return bo;
    }

}