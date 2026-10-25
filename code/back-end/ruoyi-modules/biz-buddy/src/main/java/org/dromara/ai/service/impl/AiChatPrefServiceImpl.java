package org.dromara.ai.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiSlotSet;
import org.dromara.ai.core.agent.AiSlotStore;
import org.dromara.ai.domain.bo.AiChatPrefBo;
import org.dromara.ai.domain.vo.AiChatPrefVo;
import org.dromara.ai.service.IAiChatPrefService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 对话偏好 Service 业务层处理（REQ-AI-31 继承槽位的人级设置）。
 *
 * <p>只维护「时区 / 语言」两项人级槽位，写入 {@link AiSlotStore} 的
 * {@code bizbuddy:ai:slots:user:<userId>}，优先于平台默认；空白项表示该项不设，
 * 两项皆空则删除该键、整体回落平台默认。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiChatPrefServiceImpl implements IAiChatPrefService {

    /**
     * 语言标识：形如 {@code zh-CN}、{@code en-US}、{@code zh}，主语言与可选子标签均为字母数字
     */
    private static final Pattern LOCALE_PATTERN =
        Pattern.compile("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$");

    private final AiSlotStore slotStore;

    @Override
    public AiChatPrefVo getPref() {
        Long userId = LoginHelper.getUserId();
        AiSlotSet platform = slotStore.platformDefault();
        AiSlotSet user = slotStore.getUserSlot(userId);
        AiSlotSet effective = slotStore.merge(userId, null);
        AiChatPrefVo vo = new AiChatPrefVo();
        vo.setPlatformTimezone(platform.timezone());
        vo.setPlatformLocale(platform.locale());
        vo.setTimezone(user == null ? null : user.timezone());
        vo.setLocale(user == null ? null : user.locale());
        vo.setEffectiveTimezone(effective.timezone());
        vo.setEffectiveLocale(effective.locale());
        return vo;
    }

    @Override
    public void savePref(AiChatPrefBo bo) {
        Long userId = LoginHelper.getUserId();
        String timezone = normalizeTimezone(bo == null ? null : bo.getTimezone());
        String locale = normalizeLocale(bo == null ? null : bo.getLocale());
        AiSlotSet current = slotStore.getUserSlot(userId);
        // 页面只维护时区 / 语言，其余槽位（时间窗 / 任务线）原样保留
        String timeWindow = current == null ? null : current.timeWindow();
        String taskLine = current == null ? null : current.taskLine();
        slotStore.setUserSlot(userId, new AiSlotSet(timezone, timeWindow, locale, taskLine));
        log.info("对话偏好已更新：userId={}, timezone={}, locale={}", userId, timezone, locale);
    }

    @Override
    public void clearPref() {
        Long userId = LoginHelper.getUserId();
        slotStore.setUserSlot(userId, AiSlotSet.empty());
        log.info("对话偏好已清空（回落平台默认）：userId={}", userId);
    }

    /**
     * 规范化时区：空白表示不设置；否则必须为合法 {@code ZoneId}。
     *
     * @param timezone 原始时区
     * @return 规范化后的时区；空白时返回 {@code null}
     */
    private String normalizeTimezone(String timezone) {
        String trimmed = StringUtils.trim(timezone);
        if (StringUtils.isBlank(trimmed)) {
            return null;
        }
        try {
            ZoneId.of(trimmed);
        } catch (DateTimeException e) {
            throw new ServiceException("时区无效：" + trimmed + "（示例：Asia/Shanghai、UTC）");
        }
        return trimmed;
    }

    /**
     * 规范化语言：空白表示不设置；否则必须形如 {@code zh-CN} / {@code en-US} / {@code zh}。
     *
     * @param locale 原始语言
     * @return 规范化后的语言；空白时返回 {@code null}
     */
    private String normalizeLocale(String locale) {
        String trimmed = StringUtils.trim(locale);
        if (StringUtils.isBlank(trimmed)) {
            return null;
        }
        if (!LOCALE_PATTERN.matcher(trimmed).matches()) {
            throw new ServiceException("语言标识无效：" + trimmed + "（示例：zh-CN、en-US）");
        }
        return trimmed;
    }

}
