package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 渠道外部用户标识查询结果（按手机号查平台标识）
 *
 * <p>供「渠道接入」页的辅助查询使用；「未找到」以 {@code found=false} 表达，不作为异常。</p>
 *
 * @author BizBuddy
 */
@Data
public class ChannelUserLookupVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否查到（false 表示该手机号不在企业通讯录 / 平台未识别）
     */
    private boolean found;

    /**
     * 外部用户标识（钉钉 userid / 飞书 open_id）；未查到时为空
     */
    private String externalUserId;

    /**
     * 平台返回的用户名（可空；飞书该接口不返回名称）
     */
    private String displayName;

    /**
     * 平台类型（feishu / dingtalk）
     */
    private String platformType;

}
