package org.dromara.ai.core.memory;

import org.dromara.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式记忆存储：追加、去重、撤销与持久化。
 */
@DisplayName("显式记忆存储")
class AiMemoryDeclaredStoreTest {

    @TempDir
    Path tempDir;

    private AiMemoryDeclaredStore store;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.getChat().setWorkspaceRoot(tempDir.toString());
        store = new AiMemoryDeclaredStore(properties);
    }

    @Test
    @DisplayName("追加后可按用户读取，且重复事实去重")
    void shouldAppendAndDedupe() {
        AiMemoryDeclaredStore.DeclaredMemory first = store.append("entry", "7", "用户喜欢表格", "请记住");
        AiMemoryDeclaredStore.DeclaredMemory second = store.append("entry", "7", " 用户喜欢表格 ", null);

        assertEquals(first.id(), second.id());
        assertEquals(1, store.listActive("entry", "7").size());
    }

    @Test
    @DisplayName("撤销后不再出现在生效列表")
    void shouldRevoke() {
        AiMemoryDeclaredStore.DeclaredMemory entry = store.append("entry", "7", "用户喜欢表格", null);

        assertTrue(store.revoke("entry", "7", entry.id()));
        assertTrue(store.listActive("entry", "7").isEmpty());
        assertTrue(store.listAll("entry", "7").get(0).revoked());
    }

    @Test
    @DisplayName("不同用户隔离")
    void shouldIsolateUsers() {
        store.append("entry", "7", "用户喜欢表格", null);

        assertTrue(store.listActive("entry", "8").isEmpty());
    }
}
