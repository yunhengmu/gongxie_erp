package org.dromara.ai.core.memory;

import org.dromara.ai.test.AiTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本快照：记录、去重、读取、轮转。
 */
@DisplayName("记忆版本快照")
class AiMemoryVersionStoreTest {

    @TempDir
    Path tempDir;

    private AiMemoryVersionStore store;

    @BeforeAll
    static void initStaticContext() {
        AiTestSupport.initialize();
    }

    @BeforeEach
    void setUp() {
        store = new AiMemoryVersionStore();
    }

    @Test
    @DisplayName("相同内容去重，不同内容生成新版本")
    void shouldDedupeAndRecord() {
        String first = store.record(tempDir, "memory-a", "PRE_MANUAL", "w1", 1L, null, null, 20);
        String same = store.record(tempDir, "memory-a", "MANUAL", "w1", 1L, null, null, 20);
        String second = store.record(tempDir, "memory-b", "CONSOLIDATE", "w2", null, null, null, 20);

        assertEquals(first, same);
        assertNotNull(second);
        List<AiMemoryVersionStore.VersionEntry> entries = store.list(tempDir);
        assertEquals(2, entries.size());
        assertEquals("memory-b", store.readContent(tempDir, second));
        assertTrue(entries.stream().anyMatch(entry -> "CONSOLIDATE".equals(entry.source())));
    }

    @Test
    @DisplayName("轮转只保留最新 N 份")
    void shouldRotate() {
        store.record(tempDir, "v1", "MANUAL", null, null, null, null, 20);
        store.record(tempDir, "v2", "MANUAL", null, null, null, null, 20);
        store.record(tempDir, "v3", "MANUAL", null, null, null, null, 20);

        store.rotate(tempDir, 1);

        List<AiMemoryVersionStore.VersionEntry> entries = store.list(tempDir);
        assertEquals(1, entries.size());
        assertEquals("v3", store.readContent(tempDir, entries.get(0).versionId()));
    }

    @Test
    @DisplayName("不存在的版本拒绝读取")
    void shouldRejectMissingVersion() {
        assertThrows(IllegalArgumentException.class,
            () -> store.read(tempDir, "20260101T000000000Z-deadbeef"));
    }

    @Test
    @DisplayName("版本文件被篡改时校验失败")
    void shouldRejectTamperedVersion() throws Exception {
        String id = store.record(tempDir, "original", "MANUAL", null, null, null, null, 20);
        Path file = tempDir.resolve(AiMemoryVersionStore.HISTORY_DIR)
            .resolve("MEMORY-" + id + ".md");
        Files.writeString(file, "tampered");

        assertThrows(IllegalArgumentException.class, () -> store.read(tempDir, id));
        assertTrue(Files.exists(file));
    }
}
