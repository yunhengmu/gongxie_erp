package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 公告修改传输对象（AI 公告工具入参）。
 *
 * <p>「未提供的字段保持原值」语义由服务侧依据原记录回填实现：文本字段为 {@code null} 表示不修改，
 * 非 null（含空串）表示按该值覆盖。</p>
 *
 * @author BizBuddy
 */
@Data
public class NoticeSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 公告 ID（必填）
     */
    private Long noticeId;

    /**
     * 新的公告标题（null＝不改）
     */
    private String noticeTitle;

    /**
     * 新的公告类型（1通知 2公告；null＝不改）
     */
    private String noticeType;

    /**
     * 新的公告正文（null＝不改）
     */
    private String noticeContent;

    /**
     * 新的公告状态（0正常 1关闭；null＝不改）
     */
    private String status;

    /**
     * 新的备注（null＝不改）
     */
    private String remark;

    /**
     * 操作人用户 ID（显式传入，供审计使用）
     */
    private Long operatorUserId;

}