package org.dromara.ai.core.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.AiSensitiveWord;
import org.dromara.ai.service.IAiSensitiveWordService;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 本地词库内容安全引擎（默认实现）。
 *
 * <p>词条来自 {@code ai_sensitive_word}（启用项，经 {@link IAiSensitiveWordService} 缓存）。
 * 词条可标记为正则；非法正则跳过并告警，不影响其余词条。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalKeywordSafetyEngine implements IAiContentSafetyEngine {

    /**
     * 正则编译缓存上限（超出即整体清空，避免管理员反复改词导致无界增长）
     */
    private static final int PATTERN_CACHE_LIMIT = 2000;

    private final IAiSensitiveWordService wordService;

    /**
     * 规则文本 → 已编译正则（规则少、变更低，缓存可显著降低逐片流式审核开销）
     */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    @Override
    public String engineName() {
        return "local";
    }

    @Override
    public ContentSafetyResult check(String text, SafetyScene scene) {
        if (StringUtils.isBlank(text)) {
            return ContentSafetyResult.miss();
        }
        List<AiSensitiveWord> words = wordService.enabledWords();
        if (words == null || words.isEmpty()) {
            return ContentSafetyResult.miss();
        }
        List<String> hits = new ArrayList<>();
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (AiSensitiveWord word : words) {
            if (word == null || StringUtils.isBlank(word.getWord())) {
                continue;
            }
            Pattern pattern = patternOf(word);
            if (pattern == null) {
                continue;
            }
            if (pattern.matcher(text).find()) {
                hits.add(word.getWord());
                if (StringUtils.isNotBlank(word.getCategory())) {
                    categories.add(word.getCategory());
                }
            }
        }
        return hits.isEmpty() ? ContentSafetyResult.miss()
            : new ContentSafetyResult(true, hits, new ArrayList<>(categories));
    }

    /**
     * 取（并缓存）词条的编译正则；非法规则返回 {@code null} 并告警。
     *
     * @param word 词条
     * @return 编译后的正则；非法时为 null
     */
    private Pattern patternOf(AiSensitiveWord word) {
        String rule = word.getWord();
        String cacheKey = (isRegex(word) ? "re:" : "lit:") + rule;
        Pattern cached = patternCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            String regex = isRegex(word) ? rule : Pattern.quote(rule);
            Pattern compiled = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            if (patternCache.size() >= PATTERN_CACHE_LIMIT) {
                patternCache.clear();
            }
            patternCache.put(cacheKey, compiled);
            return compiled;
        } catch (Exception e) {
            log.warn("敏感词规则非法，已跳过：{}（{}）", rule, e.getMessage());
            return null;
        }
    }

    private boolean isRegex(AiSensitiveWord word) {
        return "1".equals(word.getIsRegex());
    }

}