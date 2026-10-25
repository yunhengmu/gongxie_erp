package org.dromara.ai.core.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.config.AiProperties;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户显式记忆存储：保存用户明确要求“记住”的个人偏好与约束。
 *
 * <p>为什么独立于 {@code MEMORY.md}：合并是整文件覆盖，显式要求不能依赖合并提示词
 * “记得保留”；独立文件作为权威来源，注入时固定放在最前，任何合并都冲不掉。</p>
 *
 * <p>文件按用户隔离，与记忆目录同生命周期（{@code deleteAll} 会一并删除）。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMemoryDeclaredStore {

    /**
     * 显式记忆文件（相对用户记忆目录）
     */
    public static final String DECLARED_FILE = "memory/.declared.jsonl";

    /**
     * 单条事实最大字符数（与净化器口径一致）
     */
    private static final int MAX_FACT_CHARS = 200;

    /**
     * 单用户最大条目数
     */
    private static final int MAX_ENTRIES = 50;

    /**
     * 时间格式（展示与排序均为 UTC 偏移）
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private final AiProperties aiProperties;

    /**
     * 查询用户当前生效的显式记忆（按写入顺序）。
     *
     * @param workspaceCode 工作区命名空间（入口智能体编码）
     * @param userId        用户主键
     * @return 未撤销的条目
     */
    public List<DeclaredMemory> listActive(String workspaceCode, String userId) {
        return listAll(workspaceCode, userId).stream().filter(item -> !item.revoked()).toList();
    }

    /**
     * 查询用户全部显式记忆（含已撤销，供管理页审计）。
     *
     * @param workspaceCode 工作区命名空间
     * @param userId        用户主键
     * @return 全部条目
     */
    public List<DeclaredMemory> listAll(String workspaceCode, String userId) {
        Path file = resolveFile(workspaceCode, userId);
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        List<DeclaredMemory> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                DeclaredMemory entry = JsonUtils.parseObject(line, DeclaredMemory.class);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("读取显式记忆失败：workspace={}, userId={}, err={}", workspaceCode, userId, e.getMessage());
        }
        return entries;
    }

    /**
     * 追加一条显式记忆；同一事实已存在时直接返回原条目。
     *
     * @param workspaceCode 工作区命名空间
     * @param userId        用户主键
     * @param fact          已净化的事实
     * @param evidence      用户原话证据（可为空）
     * @return 新增或命中的条目
     */
    public synchronized DeclaredMemory append(String workspaceCode, String userId, String fact, String evidence) {
        String normalized = normalize(fact);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("显式记忆内容不能为空");
        }
        List<DeclaredMemory> all = new ArrayList<>(listAll(workspaceCode, userId));
        for (DeclaredMemory entry : all) {
            if (!entry.revoked() && normalized.equals(normalize(entry.fact()))) {
                return entry;
            }
        }
        long activeCount = all.stream().filter(item -> !item.revoked()).count();
        if (activeCount >= MAX_ENTRIES) {
            throw new IllegalStateException("显式记忆条数已达上限（" + MAX_ENTRIES + " 条）");
        }
        DeclaredMemory entry = new DeclaredMemory(
            UUID.randomUUID().toString().replace("-", ""),
            normalized,
            evidence == null ? "" : evidence.trim(),
            OffsetDateTime.now().format(TIME_FORMAT),
            false);
        all.add(entry);
        writeAll(workspaceCode, userId, all);
        return entry;
    }

    /**
     * 撤销一条显式记忆。
     *
     * @param workspaceCode 工作区命名空间
     * @param userId        用户主键
     * @param entryId       条目主键
     * @return 是否命中并撤销
     */
    public synchronized boolean revoke(String workspaceCode, String userId, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        List<DeclaredMemory> all = new ArrayList<>(listAll(workspaceCode, userId));
        boolean found = false;
        for (int index = 0; index < all.size(); index++) {
            DeclaredMemory entry = all.get(index);
            if (entry.id().equals(entryId) && !entry.revoked()) {
                all.set(index, new DeclaredMemory(entry.id(), entry.fact(), entry.evidence(),
                    entry.createdAt(), true));
                found = true;
            }
        }
        if (found) {
            writeAll(workspaceCode, userId, all);
        }
        return found;
    }

    /**
     * 解析用户记忆目录。
     *
     * @param workspaceCode 工作区命名空间
     * @param userId        用户主键
     * @return 目标文件；参数非法时返回 null
     */
    private Path resolveFile(String workspaceCode, String userId) {
        if (workspaceCode == null || workspaceCode.isBlank() || userId == null || userId.isBlank()) {
            return null;
        }
        Path userDir = Path.of(aiProperties.getChat().getWorkspaceRoot(), workspaceCode, userId)
            .toAbsolutePath().normalize();
        return userDir.resolve(DECLARED_FILE);
    }

    /**
     * 原子重写整个显式记忆文件。
     *
     * @param workspaceCode 工作区命名空间
     * @param userId        用户主键
     * @param entries       全量条目
     */
    private void writeAll(String workspaceCode, String userId, List<DeclaredMemory> entries) {
        Path file = resolveFile(workspaceCode, userId);
        if (file == null) {
            throw new IllegalArgumentException("工作区或用户不能为空");
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder content = new StringBuilder();
            for (DeclaredMemory entry : entries) {
                content.append(JsonUtils.toJsonString(entry)).append('\n');
            }
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("显式记忆写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 规范化事实文本：去首尾空白、压缩连续空白、限长。
     *
     * @param fact 原始事实
     * @return 规范化文本
     */
    private String normalize(String fact) {
        if (fact == null) {
            return "";
        }
        String normalized = fact.trim().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_FACT_CHARS ? normalized : normalized.substring(0, MAX_FACT_CHARS);
    }

    /**
     * 显式记忆条目。
     *
     * @param id        条目主键
     * @param fact      事实正文
     * @param evidence  用户原话证据
     * @param createdAt 创建时间
     * @param revoked   是否已撤销
     */
    public record DeclaredMemory(String id, String fact, String evidence, String createdAt, boolean revoked) {
    }
}
