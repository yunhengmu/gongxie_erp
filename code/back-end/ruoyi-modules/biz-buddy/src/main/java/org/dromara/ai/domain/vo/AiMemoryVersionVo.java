package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * MEMORY.md 版本展示对象。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryVersionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 版本 ID
     */
    private String versionId;

    /**
     * 创建时间（ISO）
     */
    private String createdAt;

    /**
     * 来源：PRE_MANUAL / MANUAL / PRE_CONSOLIDATE / CONSOLIDATE / PRE_RESTORE / RESTORE 等
     */
    private String source;

    /**
     * 操作人（可空）
     */
    private Long operatorId;

    /**
     * 字符数
     */
    private Integer chars;

    /**
     * 当时的合并水位（可空）
     */
    private String watermark;

    /**
     * 备注
     */
    private String note;

    /**
     * 被恢复的版本 ID（可空）
     */
    private String restoredFrom;

    /**
     * 是否为当前版本
     */
    private Boolean current;

    /**
     * 版本正文（仅详情接口返回）
     */
    private String content;
}
