package org.dromara.ai.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆合并前净化器（docs/24 §5.2）。
 *
 * <p>每日流水本身不注入系统提示词，只有合并进 {@code MEMORY.md} 后才会被注入；因此把
 * PII 脱敏、指令特征剔除与精确去重放在合并前，风险就被挡在长期记忆入口之外。</p>
 *
 * <p>处理规则固定为：密钥命中整条丢弃 → 指令特征整条丢弃 → PII 脱敏 → 超长截断 → 精确去重。
 * 非条目行（标题、空行）只做脱敏，保持原有文件结构。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiMemorySanitizer {

    /**
     * 单次合并最多纳入的条目数
     */
    public static final int MAX_ENTRIES_PER_MERGE = 50;

    /**
     * 单条记忆允许的最大字符数
     */
    private static final int MAX_ENTRY_CHARS = 200;

    /**
     * 条目行前缀（框架抽取产物为 Markdown 无序列表）
     */
    private static final String[] ENTRY_PREFIXES = {"- ", "* "};

    /**
     * 凭据类内容：命中即整条丢弃，不做脱敏保留
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)(sk-[A-Za-z0-9]{8,}|bearer\\s+[A-Za-z0-9._\\-]{8,}|AK/SK"
            + "|api[_-]?key\\s*[:=]\\s*[A-Za-z0-9._\\-]{8,})");

    /**
     * 指令注入特征：命中即整条丢弃（只作为数据看待，绝不执行）
     */
    private static final Pattern INSTRUCTION_PATTERN = Pattern.compile(
        "(?i)(ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?)"
            + "|disregard\\s+.{0,32}instructions?"
            + "|^\\s*system\\s*:"
            + "|</?memory_context>"
            + "|忽略(之前|以上|先前|前面)的?(所有)?(指令|提示|设定)"
            + "|你现在是|从现在开始你是)");

    /**
     * PII 脱敏规则；按「身份证 → 银行卡 → 手机号 → 邮箱 → 住址」顺序执行，避免长号码先被短规则切碎
     */
    private static final List<Redaction> REDACTIONS = List.of(
        new Redaction(Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "[身份证号]"),
        new Redaction(Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)"), "[银行卡号]"),
        new Redaction(Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号]"),
        new Redaction(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[邮箱]"),
        new Redaction(Pattern.compile("(地址|住址|家庭住址)[:：]\\s*[^\\n，,；;。]{4,}"), "$1：[已脱敏]")
    );

    /**
     * 净化整份流水或长期记忆正文（用于合并前就地改写、人工编辑保存）。
     *
     * @param raw 原始正文
     * @return 净化结果
     */
    public SanitizeResult sanitizeLedger(String raw) {
        if (StringUtils.isBlank(raw)) {
            return SanitizeResult.empty("");
        }
        String[] lines = raw.split("\\R", -1);
        List<String> output = new ArrayList<>(lines.length);
        Set<String> seen = new HashSet<>();
        int kept = 0;
        int dropped = 0;
        int deduped = 0;
        int redacted = 0;
        int truncated = 0;
        for (String line : lines) {
            if (!isEntry(line)) {
                // 标题/空行只做脱敏，保持原文件结构
                RedactOutcome plain = redact(line);
                redacted += plain.count();
                output.add(plain.text());
                continue;
            }
            String stripped = line.stripLeading();
            // 保留条目原有缩进，避免把 MEMORY.md 的层级结构拍平
            String indent = line.substring(0, line.length() - stripped.length());
            String body = stripped.substring(2).strip();
            if (SECRET_PATTERN.matcher(body).find() || INSTRUCTION_PATTERN.matcher(body).find()) {
                dropped++;
                continue;
            }
            RedactOutcome clean = redact(body);
            redacted += clean.count();
            String fact = clean.text().strip();
            if (fact.length() > MAX_ENTRY_CHARS) {
                fact = fact.substring(0, MAX_ENTRY_CHARS) + "…";
                truncated++;
            }
            if (!seen.add(fingerprint(fact))) {
                deduped++;
                continue;
            }
            output.add(indent + "- " + fact);
            kept++;
        }
        return new SanitizeResult(String.join("\n", output), kept, dropped, deduped, redacted, truncated);
    }

    /**
     * 净化单条事实（供记忆 SPI 写入前调用）。
     *
     * @param text 单条候选事实
     * @return 净化后的正文；命中密钥或指令特征时返回空串，表示整条丢弃
     */
    public SanitizeResult sanitizeEntry(String text) {
        if (StringUtils.isBlank(text)) {
            return SanitizeResult.empty("");
        }
        String body = text.strip();
        if (SECRET_PATTERN.matcher(body).find() || INSTRUCTION_PATTERN.matcher(body).find()) {
            return new SanitizeResult("", 0, 1, 0, 0, 0);
        }
        RedactOutcome clean = redact(body);
        String fact = clean.text().strip();
        int truncated = 0;
        if (fact.length() > MAX_ENTRY_CHARS) {
            fact = fact.substring(0, MAX_ENTRY_CHARS) + "…";
            truncated = 1;
        }
        return new SanitizeResult(fact, 1, 0, 0, clean.count(), truncated);
    }

    /**
     * 统计正文中的条目数量（合并输入预算用）。
     *
     * @param content 正文
     * @return 条目数
     */
    public int countEntries(String content) {
        if (StringUtils.isBlank(content)) {
            return 0;
        }
        int count = 0;
        for (String line : content.split("\\R")) {
            if (isEntry(line)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断是否为条目行。
     */
    private boolean isEntry(String line) {
        String stripped = line.stripLeading();
        for (String prefix : ENTRY_PREFIXES) {
            if (stripped.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行全部 PII 脱敏规则。
     */
    private RedactOutcome redact(String text) {
        String current = text;
        int count = 0;
        for (Redaction redaction : REDACTIONS) {
            Matcher matcher = redaction.pattern().matcher(current);
            if (matcher.find()) {
                count++;
                current = matcher.replaceAll(redaction.replacement());
            }
        }
        return new RedactOutcome(current, count);
    }

    /**
     * 生成精确去重指纹：规范化空白与大小写后取 SHA-256。
     */
    private String fingerprint(String fact) {
        String normalized = fact.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须支持 SHA-256；退化时用原文兜底，仍能保证精确去重
            log.warn("记忆去重指纹算法不可用，退化为原文比较：{}", e.getMessage());
            return normalized;
        }
    }

    /**
     * 一条脱敏规则。
     *
     * @param pattern     匹配模式
     * @param replacement 替换文本
     */
    private record Redaction(Pattern pattern, String replacement) {
    }

    /**
     * 脱敏结果（正文 + 命中次数）。
     *
     * @param text  脱敏后正文
     * @param count 命中次数
     */
    private record RedactOutcome(String text, int count) {
    }

    /**
     * 净化结果与计数。
     *
     * @param content   净化后正文
     * @param kept      保留条目数
     * @param dropped   丢弃条目数（含密钥/指令特征）
     * @param deduped   去重丢弃条目数
     * @param redacted  脱敏命中次数
     * @param truncated 截断条目数
     */
    public record SanitizeResult(String content, int kept, int dropped, int deduped, int redacted,
                                 int truncated) {

        /**
         * 空结果。
         *
         * @param content 正文
         * @return 空计数结果
         */
        public static SanitizeResult empty(String content) {
            return new SanitizeResult(content, 0, 0, 0, 0, 0);
        }

        /**
         * 是否产生了任何净化动作（用于决定是否需要回写流水）。
         *
         * @return 有动作时返回 true
         */
        public boolean changed() {
            return dropped + deduped + redacted + truncated > 0;
        }

        /**
         * 累加两轮统计（同一次合并涉及多个流水文件时汇总审计）。
         *
         * @param other 另一轮结果
         * @return 汇总结果（正文取当前结果）
         */
        public SanitizeResult plus(SanitizeResult other) {
            if (other == null) {
                return this;
            }
            return new SanitizeResult(content, kept + other.kept, dropped + other.dropped,
                deduped + other.deduped, redacted + other.redacted, truncated + other.truncated);
        }
    }
}
