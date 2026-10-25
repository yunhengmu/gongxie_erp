package org.dromara.ai.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * MEMORY.md 版本快照（T19）。
 *
 * <p>文件布局：{@code memory/history/index.jsonl} + {@code MEMORY-<ts>-<hash>.md}。
 * 只存 MEMORY.md，不含每日流水；版本按“逻辑操作”记录（人工编辑、合并前/后、恢复前），
 * 内容哈希相同则去重。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiMemoryVersionStore {

    /**
     * 版本目录（相对 memory 目录）
     */
    public static final String HISTORY_DIR = "history";

    /**
     * 版本索引文件
     */
    public static final String INDEX_FILE = "index.jsonl";

    /**
     * 版本 ID 时间格式（UTC）
     */
    private static final DateTimeFormatter VERSION_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * 记录一个版本；内容为空或与最新版本相同则跳过。
     *
     * @param memoryDir  记忆目录
     * @param content    MEMORY.md 全文
     * @param source     来源（PRE_MANUAL / MANUAL / PRE_CONSOLIDATE / CONSOLIDATE / RESTORE 等）
     * @param watermark  当时的合并水位（可空）
     * @param operatorId 操作人（可空）
     * @param note       备注（可空）
     * @param restoredFrom 被恢复的版本 ID（可空）
     * @param retention  保留份数
     * @return 版本 ID；跳过时返回最新版本 ID 或 null
     */
    public synchronized String record(Path memoryDir, String content, String source, String watermark,
                                      Long operatorId, String note, String restoredFrom, int retention) {
        if (content == null || content.isBlank()) {
            return null;
        }
        List<VersionEntry> entries = listEntries(memoryDir);
        String hash = sha256(content);
        if (!entries.isEmpty() && hash.equals(entries.get(entries.size() - 1).sha256())) {
            return entries.get(entries.size() - 1).versionId();
        }
        String versionId = VERSION_TIME.format(Instant.now()) + "-" + hash.substring(0, 8);
        Path dir = memoryDir.resolve(HISTORY_DIR);
        Path file = dir.resolve("MEMORY-" + versionId + ".md");
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve(file.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            VersionEntry entry = new VersionEntry(versionId, file.getFileName().toString(),
                Instant.now().toString(), source, operatorId, content.length(), hash,
                watermark == null ? "" : watermark, note == null ? "" : note,
                restoredFrom == null ? "" : restoredFrom);
            appendIndex(memoryDir, entry);
            rotate(memoryDir, retention);
            return versionId;
        } catch (IOException e) {
            throw new IllegalStateException("记忆版本快照写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 列出版本（新→旧）。
     *
     * @param memoryDir 记忆目录
     * @return 版本列表
     */
    public synchronized List<VersionEntry> list(Path memoryDir) {
        List<VersionEntry> entries = listEntries(memoryDir);
        entries.sort(Comparator.comparing(VersionEntry::createdAt).reversed());
        return entries;
    }

    /**
     * 读取版本详情并校验哈希。
     *
     * @param memoryDir 记忆目录
     * @param versionId 版本 ID
     * @return 版本详情
     */
    public synchronized VersionEntry read(Path memoryDir, String versionId) {
        VersionEntry entry = listEntries(memoryDir).stream()
            .filter(item -> item.versionId().equals(versionId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("记忆版本不存在：" + versionId));
        Path file = memoryDir.resolve(HISTORY_DIR).resolve(entry.file());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("记忆版本文件已丢失：" + versionId);
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!sha256(content).equals(entry.sha256())) {
                throw new IllegalArgumentException("记忆版本内容校验失败：" + versionId);
            }
            return entry;
        } catch (IOException e) {
            throw new IllegalStateException("记忆版本读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 读取版本正文。
     *
     * @param memoryDir 记忆目录
     * @param versionId 版本 ID
     * @return 正文
     */
    public synchronized String readContent(Path memoryDir, String versionId) {
        VersionEntry entry = read(memoryDir, versionId);
        try {
            return Files.readString(memoryDir.resolve(HISTORY_DIR).resolve(entry.file()),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("记忆版本读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 判断当前正文是否与某版本一致（用于列表标记“当前版本”）。
     *
     * @param entry   版本
     * @param content 当前正文
     * @return 一致时返回 true
     */
    public boolean matches(VersionEntry entry, String content) {
        return entry != null && content != null && sha256(content).equals(entry.sha256());
    }

    /**
     * 轮转版本：只保留最新 retention 份。
     *
     * @param memoryDir 记忆目录
     * @param retention 保留份数
     */
    public synchronized void rotate(Path memoryDir, int retention) {
        int keep = Math.max(1, retention);
        List<VersionEntry> entries = listEntries(memoryDir);
        if (entries.size() <= keep) {
            return;
        }
        List<VersionEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(VersionEntry::createdAt));
        List<VersionEntry> removed = sorted.subList(0, sorted.size() - keep);
        for (VersionEntry entry : removed) {
            try {
                Files.deleteIfExists(memoryDir.resolve(HISTORY_DIR).resolve(entry.file()));
            } catch (IOException e) {
                log.warn("删除旧记忆版本失败：file={}, err={}", entry.file(), e.getMessage());
            }
        }
        List<VersionEntry> retained = sorted.subList(sorted.size() - keep, sorted.size());
        writeIndex(memoryDir, retained);
    }

    /**
     * 读取索引。
     */
    private List<VersionEntry> listEntries(Path memoryDir) {
        Path index = memoryDir.resolve(HISTORY_DIR).resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return new ArrayList<>();
        }
        List<VersionEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                VersionEntry entry = JsonUtils.parseObject(line, VersionEntry.class);
                if (entry != null && entry.versionId() != null) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("读取记忆版本索引失败：dir={}, err={}", memoryDir, e.getMessage());
        }
        return entries;
    }

    /**
     * 追加索引行。
     */
    private void appendIndex(Path memoryDir, VersionEntry entry) throws IOException {
        Path index = memoryDir.resolve(HISTORY_DIR).resolve(INDEX_FILE);
        Files.writeString(index, JsonUtils.toJsonString(entry) + "\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * 原子重写索引。
     */
    private void writeIndex(Path memoryDir, List<VersionEntry> entries) {
        Path index = memoryDir.resolve(HISTORY_DIR).resolve(INDEX_FILE);
        try {
            Path temp = index.resolveSibling(INDEX_FILE + ".tmp");
            StringBuilder content = new StringBuilder();
            for (VersionEntry entry : entries) {
                content.append(JsonUtils.toJsonString(entry)).append('\n');
            }
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            Files.move(temp, index, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("重写记忆版本索引失败：dir={}, err={}", memoryDir, e.getMessage());
        }
    }

    /**
     * 计算内容 SHA-256。
     */
    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("记忆版本哈希计算失败", e);
        }
    }

    /**
     * 版本元数据。
     */
    public record VersionEntry(String versionId, String file, String createdAt, String source,
                               Long operatorId, int chars, String sha256, String watermark,
                               String note, String restoredFrom) {
    }
}
