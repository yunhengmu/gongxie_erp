package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.domain.bo.AiChatPrefBo;
import org.dromara.ai.domain.vo.AiChatPrefVo;
import org.dromara.ai.service.IAiChatPrefService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话偏好 Controller（REQ-AI-31 继承槽位的人级设置）。
 *
 * <p>只维护「时区 / 语言」两项，作用于当前登录用户，覆盖平台默认。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/chat/pref")
public class AiChatPrefController extends BaseController {

    private final IAiChatPrefService chatPrefService;

    /**
     * 查询当前用户的对话偏好（回带平台默认 / 已设置 / 实际生效三段值）
     */
    @SaCheckPermission("ai:chat:pref:query")
    @GetMapping
    public R<AiChatPrefVo> getPref() {
        return R.ok(chatPrefService.getPref());
    }

    /**
     * 保存当前用户的对话偏好（时区 / 语言，空白项表示不设置）
     */
    @SaCheckPermission("ai:chat:pref:edit")
    @Log(title = "对话偏好", businessType = BusinessType.UPDATE)
    @PutMapping
    public R<Void> savePref(@Validated @RequestBody AiChatPrefBo bo) {
        chatPrefService.savePref(bo);
        return R.ok();
    }

    /**
     * 清空当前用户的人级设置，使两项均回落平台默认
     */
    @SaCheckPermission("ai:chat:pref:edit")
    @Log(title = "对话偏好", businessType = BusinessType.UPDATE)
    @DeleteMapping
    public R<Void> clearPref() {
        chatPrefService.clearPref();
        return R.ok();
    }

}
