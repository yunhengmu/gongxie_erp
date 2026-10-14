package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对话偏好写入请求体（时区 / 语言，REQ-AI-31 槽位的人级设置）。
 *
 * <p>两项均可选：空白表示**该项不设**（沿用平台默认）；两项都空等价于清空人级设置。
 * 写入仅来自平台页面，模型无权改写。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiChatPrefBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 时区（如 Asia/Shanghai）
     */
    private String timezone;

    /**
     * 语言（如 zh-CN、en-US）
     */
    private String locale;

}
