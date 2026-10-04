package org.dromara.ai.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 「当前时间」的统一口径：时区解析与文本渲染。
 *
 * <p>由两处共用，避免口径分叉：{@link CurrentTimeMiddleware}（每轮注入 system prompt，分钟精度）
 * 与 {@code org.dromara.ai.core.agent.tools.GetCurrentTimeTool}（模型主动调用，秒精度）。两处**必须**
 * 用同一时区配置，否则会出现「注入说 13:11、工具说 05:11」的自相矛盾。</p>
 *
 * @author BizBuddy
 */
@Slf4j
public final class CurrentTimeSupport {

    /**
     * 分钟精度（注入 system prompt 用：避免模型把秒当作业务参数）
     */
    public static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 秒精度（工具返回用：模型主动问时间时通常需要精确值）
     */
    public static final DateTimeFormatter SECOND_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 中文星期名（索引＝{@link java.time.DayOfWeek#getValue()}，0 位留空）
     */
    private static final String[] WEEKDAYS = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private CurrentTimeSupport() {
    }

    /**
     * 解析生效时区：配置优先，留空或非法时回退 JVM 默认时区（不因一个配置项导致整轮对话失败）。
     *
     * @param configured 配置的时区标识（IANA，如 {@code Asia/Shanghai}）
     * @return 生效时区
     */
    public static ZoneId resolveZone(String configured) {
        if (StringUtils.isNotBlank(configured)) {
            try {
                return ZoneId.of(StringUtils.trim(configured));
            } catch (DateTimeException e) {
                log.warn("当前时间的时区配置无效，回退 JVM 默认时区：zone={}", configured);
            }
        }
        return ZoneId.systemDefault();
    }

    /**
     * 渲染当前时间为中文文本。
     *
     * @param zone      生效时区
     * @param formatter 精度（{@link #MINUTE_FORMATTER} 或 {@link #SECOND_FORMATTER}）
     * @return 形如 {@code 2026-09-27 13:11（周日，时区 Asia/Shanghai）}
     */
    public static String render(ZoneId zone, DateTimeFormatter formatter) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        return now.format(formatter) + "（" + WEEKDAYS[now.getDayOfWeek().getValue()] + "，时区 " + zone.getId() + "）";
    }

}