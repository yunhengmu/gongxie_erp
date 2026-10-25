package org.dromara.ai.core.safety;

import org.dromara.ai.domain.AiSensitiveWord;
import org.dromara.ai.service.IAiSensitiveWordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalKeywordSafetyEngine} 单元测试：字面词与正则词命中。
 */
@DisplayName("本地词库内容安全引擎")
class LocalKeywordSafetyEngineTest {

    private final IAiSensitiveWordService wordService = mock(IAiSensitiveWordService.class);

    private final LocalKeywordSafetyEngine engine = new LocalKeywordSafetyEngine(wordService);

    private static AiSensitiveWord word(String value, String isRegex, String category) {
        AiSensitiveWord word = new AiSensitiveWord();
        word.setWord(value);
        word.setIsRegex(isRegex);
        word.setCategory(category);
        return word;
    }

    @Test
    @DisplayName("字面词命中并返回类别")
    void shouldHitLiteralWord() {
        when(wordService.enabledWords()).thenReturn(List.of(word("违规词", "0", "违禁")));

        ContentSafetyResult result = engine.check("这里包含违规词内容", SafetyScene.INPUT, null);

        assertTrue(result.hit());
        assertTrue(result.hits().contains("违规词"));
        assertTrue(result.categories().contains("违禁"));
    }

    @Test
    @DisplayName("正则词命中")
    void shouldHitRegexWord() {
        when(wordService.enabledWords()).thenReturn(List.of(word("\\d{6,}", "1", "数字刷屏")));

        assertTrue(engine.check("编号 123456789", SafetyScene.OUTPUT, null).hit());
        assertFalse(engine.check("没有长数字", SafetyScene.OUTPUT, null).hit());
    }

    @Test
    @DisplayName("未命中或空文本返回未命中")
    void shouldMissWhenAbsent() {
        when(wordService.enabledWords()).thenReturn(List.of(word("违规词", "0", null)));

        assertFalse(engine.check("正常内容", SafetyScene.INPUT, null).hit());
        assertFalse(engine.check(null, SafetyScene.INPUT, null).hit());
    }

    @Test
    @DisplayName("非法正则被跳过，不影响其余词条")
    void shouldSkipInvalidRegex() {
        when(wordService.enabledWords()).thenReturn(List.of(word("[unclosed", "1", null), word("违规词", "0", null)));

        assertTrue(engine.check("包含违规词的句子", SafetyScene.INPUT, null).hit());
    }

}