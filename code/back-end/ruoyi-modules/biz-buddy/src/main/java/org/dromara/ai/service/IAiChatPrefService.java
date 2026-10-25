package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiChatPrefBo;
import org.dromara.ai.domain.vo.AiChatPrefVo;

/**
 * 对话偏好 Service 接口（REQ-AI-31 继承槽位的人级设置）。
 *
 * <p>只维护「时区 / 语言」两项人级槽位，覆盖平台默认；存储走 {@link org.dromara.ai.core.agent.AiSlotStore}，
 * 不加表。写入仅来自平台页面（登录态），模型无权改写。</p>
 *
 * @author BizBuddy
 */
public interface IAiChatPrefService {

    /**
     * 查询当前用户的对话偏好。
     *
     * <p>回带三段值：平台默认（只读）、人级已设置值（未设置为 {@code null}）、实际生效值。</p>
     *
     * @return 对话偏好视图
     */
    AiChatPrefVo getPref();

    /**
     * 保存当前用户的对话偏好（时区 / 语言，空白项表示不设置）。
     *
     * @param bo 写入请求体
     */
    void savePref(AiChatPrefBo bo);

    /**
     * 清空当前用户的人级设置，使时区与语言均回落平台默认。
     */
    void clearPref();

}
