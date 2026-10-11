package org.dromara.ai.core.safety;

import java.util.List;

/**
 * 内容安全引擎的校验结果。
 *
 * @param hit        是否命中
 * @param hits       命中项（命中词或类别；未命中为空列表）
 * @param categories 命中类别（去重；未命中为空列表）
 * @author BizBuddy
 */
public record ContentSafetyResult(boolean hit, List<String> hits, List<String> categories) {

    /**
     * 未命中。
     *
     * @return 空结果
     */
    public static ContentSafetyResult miss() {
        return new ContentSafetyResult(false, List.of(), List.of());
    }

}