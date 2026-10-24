package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSensitiveWord;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 敏感词库视图对象 ai_sensitive_word
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSensitiveWord.class)
public class AiSensitiveWordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 敏感词或正则表达式
     */
    private String word;

    /**
     * 是否正则：0否（子串匹配） 1是
     */
    private String isRegex;

    /**
     * 分类（可空，供分组管理与统计）
     */
    private String category;

    /**
     * 是否启用：1启用 0禁用
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}