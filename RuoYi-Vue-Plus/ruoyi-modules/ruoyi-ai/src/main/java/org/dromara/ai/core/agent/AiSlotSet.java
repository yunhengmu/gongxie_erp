package org.dromara.ai.core.agent;

import org.dromara.common.core.utils.StringUtils;

/**
 * 继承槽位（REQ-AI-31）：跨轮 / 跨专家持续生效的运行期既定事实。
 *
 * <p>槽位集＝时区 / 时间窗 / 语言 /（可选）任务线，**不含「环境」**（P6：环境仍由技能来源配置的
 * 既有机制承载，二者不打通）。</p>
 *
 * <p><b>写入仅来自平台</b>（登录态 / 时间中间件 / 页面设置），**模型无权改写**；存储为 Redis 两级：
 * 人级 {@code bizbuddy:ai:slots:user:<userId>} + 会话级覆盖 {@code bizbuddy:ai:slots:session:<sessionId>}，
 * 优先级为**会话显式 &gt; 该用户近期 &gt; 平台默认**。</p>
 *
 * <p>与装配的关系：本对象是**每轮运行期上下文**，不进装配指纹（与候选专家清单同一思路）。</p>
 *
 * @param timezone   时区（如 {@code Asia/Shanghai}）
 * @param timeWindow 时间窗（如「近 7 天」）
 * @param locale     语言（如 {@code zh-CN}）
 * @param taskLine   任务线（可选，自由文本）
 * @author BizBuddy
 */
public record AiSlotSet(String timezone, String timeWindow, String locale, String taskLine) {

    /**
     * 空槽位（四个槽位均未设置）。
     *
     * @return 空槽位
     */
    public static AiSlotSet empty() {
        return new AiSlotSet(null, null, null, null);
    }

    /**
     * 用更具体的槽位覆盖本槽位（仅覆盖方的**非空**字段生效）。
     *
     * <p>调用顺序即优先级：{@code 平台默认.overlay(人级).overlay(会话级)}。</p>
     *
     * @param override 覆盖方（可空）
     * @return 覆盖后的槽位
     */
    public AiSlotSet overlay(AiSlotSet override) {
        if (override == null) {
            return this;
        }
        return new AiSlotSet(
            StringUtils.isBlank(override.timezone) ? timezone : override.timezone,
            StringUtils.isBlank(override.timeWindow) ? timeWindow : override.timeWindow,
            StringUtils.isBlank(override.locale) ? locale : override.locale,
            StringUtils.isBlank(override.taskLine) ? taskLine : override.taskLine);
    }

    /**
     * 是否四个槽位都未设置。
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return StringUtils.isBlank(timezone) && StringUtils.isBlank(timeWindow)
            && StringUtils.isBlank(locale) && StringUtils.isBlank(taskLine);
    }

    /**
     * 渲染为注入入口智能体 system prompt 的「## 继承槽位」段落正文。
     *
     * @return 多行文本；无任何槽位时返回单行占位说明
     */
    public String render() {
        if (isEmpty()) {
            return "（本轮无继承槽位）";
        }
        StringBuilder sb = new StringBuilder();
        appendBullet(sb, "时区", timezone);
        appendBullet(sb, "时间窗", timeWindow);
        appendBullet(sb, "语言", locale);
        appendBullet(sb, "任务线", taskLine);
        return sb.toString().stripTrailing();
    }

    /**
     * 渲染为并入 {@code task_context} 的一行文本（传给专家，使其无需追问已知槽位）。
     *
     * @return 形如 {@code 时区=Asia/Shanghai；时间窗=近 7 天}；无槽位时返回空串
     */
    public String renderInline() {
        StringBuilder sb = new StringBuilder();
        appendInline(sb, "时区", timezone);
        appendInline(sb, "时间窗", timeWindow);
        appendInline(sb, "语言", locale);
        appendInline(sb, "任务线", taskLine);
        return sb.toString();
    }

    /**
     * 追加一条「- 名称：值」要点（空值跳过）。
     *
     * @param sb   目标
     * @param name 槽位名
     * @param value 槽位值
     */
    private static void appendBullet(StringBuilder sb, String name, String value) {
        if (!StringUtils.isBlank(value)) {
            sb.append("- ").append(name).append('：').append(value).append('\n');
        }
    }

    /**
     * 追加一条「名称=值」（空值跳过）。
     *
     * @param sb   目标
     * @param name 槽位名
     * @param value 槽位值
     */
    private static void appendInline(StringBuilder sb, String name, String value) {
        if (!StringUtils.isBlank(value)) {
            if (!sb.isEmpty()) {
                sb.append('；');
            }
            sb.append(name).append('=').append(value);
        }
    }

}