package org.dromara.ai.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import io.github.linpeilie.Converter;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.dromara.ai.core.agent.AgentRegistry;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.AiAgent;
import org.dromara.ai.domain.bo.AiMemoryConfigBo;
import org.dromara.ai.domain.bo.AiMemoryContentBo;
import org.dromara.ai.domain.vo.AiMemoryContentVo;
import org.dromara.ai.domain.vo.AiMemoryFileVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.context.support.StaticApplicationContext;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link AiMemoryServiceImpl} 单元测试：覆盖记忆文件读写、路径校验与合并流程，全程使用 mock 工作区，不连真实存储。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 长期记忆 Service 单元测试")
class AiMemoryServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    private IAiAgentService agentService;

    @Mock
    private AgentRegistry agentRegistry;

    @Mock
    private AiRuntimeConfigStore runtimeConfigStore;

    @InjectMocks
    private AiMemoryServiceImpl memoryService;

    /**
     * 注入内存 Spring 容器，供 RedisUtils 静态初始化使用，不连接真实 Redis。
     */
    @BeforeAll
    static void initStaticContext() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("converter", mock(Converter.class));
        context.getBeanFactory().registerSingleton("jsonMapper", JsonMapper.builder().build());
        context.getBeanFactory().registerSingleton("redissonClient", mock(RedissonClient.class));
        context.getBeanFactory().registerSingleton("identifierGenerator", mock(IdentifierGenerator.class));
        context.refresh();
        new SpringUtil().setApplicationContext(context);
    }

    /**
     * 构造一条每日流水文件，模拟工作区已落盘的事实流水。
     */
    @BeforeEach
    void prepareDailyLedger() throws IOException {
        Files.writeString(tempDir.resolve("2024-01-01.md"), "fact");
    }

    /**
     * 验证列出记忆文件清单区分长期记忆与每日流水。
     */
    @Test
    @DisplayName("列出记忆文件清单")
    void shouldListMemoryFiles() {
        WorkspaceManager workspace = stubWorkspace();
        when(workspace.readMemoryMd(any())).thenReturn("mem");
        when(workspace.listMemoryFilePaths(any())).thenReturn(List.of("MEMORY.md", "memory/2024-01-01.md"));
        when(workspace.getMemoryDir(any())).thenReturn(tempDir);

        List<AiMemoryFileVo> files = memoryService.listFiles(1L, 7L);

        assertEquals(2, files.size());
        assertEquals("MEMORY.md", files.get(0).getPath());
        assertEquals("MEMORY", files.get(0).getType());
        assertEquals(3, files.get(0).getChars());
        assertEquals("DAILY", files.get(1).getType());
        assertEquals("2024-01-01", files.get(1).getDate());
        assertEquals(4, files.get(1).getChars());
    }

    /**
     * 验证读取长期记忆正文。
     */
    @Test
    @DisplayName("读取长期记忆正文")
    void shouldReadMemoryMd() {
        WorkspaceManager workspace = stubWorkspace();
        when(workspace.readMemoryMd(any())).thenReturn("mem");

        AiMemoryContentVo vo = memoryService.readContent(1L, 7L, "MEMORY.md");

        assertEquals("MEMORY.md", vo.getPath());
        assertEquals("mem", vo.getContent());
    }

    /**
     * 验证读取每日流水正文。
     */
    @Test
    @DisplayName("读取每日流水正文")
    void shouldReadDailyFile() {
        WorkspaceManager workspace = stubWorkspace();
        when(workspace.getMemoryDir(any())).thenReturn(tempDir);

        AiMemoryContentVo vo = memoryService.readContent(1L, 7L, "memory/2024-01-01.md");

        assertEquals("fact", vo.getContent());
    }

    /**
     * 验证路径非法时抛业务异常，阻断越权访问。
     */
    @Test
    @DisplayName("非法路径被拒绝")
    void shouldRejectInvalidPath() {
        assertThrows(ServiceException.class, () -> memoryService.readContent(1L, 7L, "../secret.txt"));
        assertThrows(ServiceException.class, () -> memoryService.readContent(1L, 7L, ""));
    }

    /**
     * 验证写入记忆文件委托工作区受管写入。
     */
    @Test
    @DisplayName("写入记忆文件")
    void shouldWriteContent() {
        WorkspaceManager workspace = stubWorkspace();
        AiMemoryContentBo bo = new AiMemoryContentBo();
        bo.setAgentId(1L);
        bo.setUserId(7L);
        bo.setPath("MEMORY.md");
        bo.setContent("hello");

        memoryService.writeContent(bo);

        verify(workspace).writeUtf8WorkspaceRelative(any(), eq("MEMORY.md"), eq("hello"));
    }

    /**
     * 验证无每日流水时合并直接跳过，不调用记忆模型。
     */
    @Test
    @DisplayName("无流水时跳过合并")
    void shouldSkipConsolidateWhenNoLedger() {
        WorkspaceManager workspace = stubWorkspace();
        when(runtimeConfigStore.memory()).thenReturn(policy());
        when(workspace.readMemoryMd(any())).thenReturn("old");
        when(workspace.listMemoryFilePaths(any())).thenReturn(List.of("MEMORY.md"));

        assertDoesNotThrow(() -> memoryService.consolidate(1L, 7L));
        verify(agentRegistry, never()).memoryModelFor(any());
    }

    /**
     * 验证手动合并调用记忆模型并把结果写回 MEMORY.md。
     */
    @Test
    @DisplayName("手动合并写回长期记忆")
    void shouldConsolidateAndWriteBack() {
        WorkspaceManager workspace = stubWorkspace();
        when(runtimeConfigStore.memory()).thenReturn(policy());
        when(workspace.readMemoryMd(any())).thenReturn("old");
        when(workspace.listMemoryFilePaths(any())).thenReturn(List.of("MEMORY.md", "memory/2024-01-01.md"));
        when(workspace.getMemoryDir(any())).thenReturn(tempDir);
        Model model = mock(Model.class);
        when(agentRegistry.memoryModelFor(any())).thenReturn(model);
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(response("merged")));

        memoryService.consolidate(1L, 7L);

        verify(workspace).writeUtf8WorkspaceRelative(any(), eq("MEMORY.md"), eq("merged"));
    }

    /**
     * 验证模型未返回有效内容时合并失败抛业务异常。
     */
    @Test
    @DisplayName("模型无输出时合并失败")
    void shouldFailWhenModelReturnsBlank() {
        WorkspaceManager workspace = stubWorkspace();
        when(runtimeConfigStore.memory()).thenReturn(policy());
        when(workspace.readMemoryMd(any())).thenReturn("old");
        when(workspace.listMemoryFilePaths(any())).thenReturn(List.of("MEMORY.md", "memory/2024-01-01.md"));
        when(workspace.getMemoryDir(any())).thenReturn(tempDir);
        Model model = mock(Model.class);
        when(agentRegistry.memoryModelFor(any())).thenReturn(model);
        when(model.stream(any(), any(), any())).thenReturn(Flux.empty());

        assertThrows(ServiceException.class, () -> memoryService.consolidate(1L, 7L));
    }

    /**
     * 验证归档目录按归档保留期清理：超过流水保留期但未到归档保留期的文件必须保留。
     */
    @Test
    @DisplayName("归档目录按独立保留期清理")
    void shouldCleanupArchiveWithOwnRetention() throws IOException {
        WorkspaceManager workspace = stubWorkspace();
        when(runtimeConfigStore.memory()).thenReturn(policy());
        when(workspace.getMemoryDir(any())).thenReturn(tempDir);
        Path archive = Files.createDirectories(tempDir.resolve("archive"));
        String dailyName = LocalDate.now().minusDays(100) + ".md";
        String archiveName = LocalDate.now().minusDays(200) + ".md";
        Files.writeString(tempDir.resolve(dailyName), "daily");
        Files.writeString(archive.resolve(archiveName), "archived");

        int removed = memoryService.cleanup(1L, 7L);

        assertFalse(Files.exists(tempDir.resolve(dailyName)));
        assertTrue(Files.exists(archive.resolve(archiveName)));
        assertTrue(removed >= 1);
    }

    private WorkspaceManager stubWorkspace() {
        AiAgent definition = mock(AiAgent.class);
        HarnessAgent harness = mock(HarnessAgent.class);
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(agentService.getEnabledAgent(anyLong())).thenReturn(definition);
        when(agentRegistry.get(definition)).thenReturn(harness);
        when(harness.getWorkspaceManager()).thenReturn(workspace);
        return workspace;
    }

    private AiMemoryConfigBo policy() {
        AiMemoryConfigBo policy = new AiMemoryConfigBo();
        policy.setEnabled(true);
        policy.setConsolidationMaxTokens(1000);
        policy.setConsolidationMinGapMinutes(10);
        policy.setDailyFileRetentionDays(90);
        policy.setArchiveRetentionDays(365);
        policy.setSessionRetentionDays(180);
        return policy;
    }

    private ChatResponse response(String text) {
        TextBlock block = TextBlock.builder().text(text).build();
        return new ChatResponse(null, List.<ContentBlock>of(block), null, null, null);
    }

}