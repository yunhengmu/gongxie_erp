package org.dromara.system.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告查询结果传输对象（供 AI 公告工具渲染清单与详情）。
 *
 * @author BizBuddy
 */
@Data
public class NoticeDetailDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 公告 ID
     */
    private Long noticeId;

    /**
     * 公告标题
     */
    private String noticeTitle;

    /**
     * 公告类型（1通知 2公告）
     */
    private String noticeType;

    /**
     * 公告正文
     */
    private String noticeContent;

    /**
     * 公告状态（0正常 1关闭）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建人用户 ID
     */
    private Long createBy;

    /**
     * 创建人昵称（由服务侧补齐，便于模型直接引用）
     */
    private String createByName;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}