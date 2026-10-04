package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 公告发布传输对象
 *
 * @author BizBuddy
 */
@Data
public class NoticeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 公告标题
     */
    private String noticeTitle;

    /**
     * 公告类型（1通知 2公告）
     */
    private String noticeType;

    /**
     * 公告内容
     */
    private String noticeContent;

    /**
     * 公告状态（0正常 1关闭）
     */
    private String status;

    /**
     * 操作人用户 ID（显式传入，用于落库 create_by 与审计）
     */
    private Long operatorUserId;

}