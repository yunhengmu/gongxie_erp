package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 显式记忆展示对象。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryDeclaredVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 条目主键
     */
    private String id;

    /**
     * 事实正文
     */
    private String fact;

    /**
     * 用户原话证据
     */
    private String evidence;

    /**
     * 创建时间
     */
    private String createdAt;

    /**
     * 是否已撤销
     */
    private Boolean revoked;
}
