package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长期记忆配置业务对象（REQ-AI-09）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.chat.*}，页面保存后写入 Redis 覆盖默认值，
 * 并参与智能体装配指纹判定，保存后下一次对话即按新策略装配生效。</p>
 *
 * <p>记忆本身按用户隔离（AgentScope 默认 {@code IsolationScope.USER}），存放在各智能体工作区下；
 * 本对象只描述「开关与维护参数」，不含具体记忆内容。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用长期记忆（关闭时不注入记忆、不自动抽取事实）
     */
    private boolean enabled;

    /**
     * 记忆管道模式：framework / shadow / platform。
     *
     * <p>platform 会关闭框架记忆 hooks 与记忆工具，由平台接管抽取、注入、合并与维护。</p>
     */
    @Pattern(regexp = "^(framework|shadow|platform)$",
        message = "记忆管道模式只能是 framework / shadow / platform")
    private String pipelineMode;

    /**
     * 平台抽取时携带的只读上下文消息条数
     */
    @Min(value = 0, message = "抽取上下文消息条数不能小于 0")
    @Max(value = 12, message = "抽取上下文消息条数不能超过 12")
    private int extractContextMessages = 6;

    /**
     * 平台抽取的只读上下文字符上限
     */
    @Min(value = 1000, message = "抽取上下文字符上限不能小于 1000")
    @Max(value = 50000, message = "抽取上下文字符上限不能超过 50000")
    private int extractContextMaxChars = 8000;

    /**
     * 平台抽取时“已有记忆摘要”的字符上限
     */
    @Min(value = 0, message = "已有记忆摘要上限不能小于 0")
    @Max(value = 10000, message = "已有记忆摘要上限不能超过 10000")
    private int extractKnownMemoryMaxChars = 2000;

    /**
     * 显式记忆注入的 Token 上限（独立于 MEMORY.md 注入预算）
     */
    @Min(value = 100, message = "显式记忆注入上限不能小于 100")
    @Max(value = 4000, message = "显式记忆注入上限不能超过 4000")
    private int injectDeclaredMaxTokens = 800;

    /**
     * 记忆注入 system prompt 的上下文 Token 上限
     */
    @Min(value = 500, message = "记忆注入上限必须不小于 500")
    @Max(value = 200000, message = "记忆注入上限不能超过 200000")
    private int maxContextTokens;

    /**
     * 合并时允许读取的每日流水 Token 上限
     */
    @Min(value = 500, message = "合并 Token 上限必须不小于 500")
    @Max(value = 100000, message = "合并 Token 上限不能超过 100000")
    private int consolidationMaxTokens;

    /**
     * 两次合并之间的最小间隔（分钟）
     */
    @Min(value = 1, message = "合并最小间隔必须大于 0")
    @Max(value = 1440, message = "合并最小间隔不能超过 1440 分钟")
    private int consolidationMinGapMinutes;

    /**
     * 每日流水归档保留天数
     */
    @Min(value = 1, message = "流水保留天数必须大于 0")
    @Max(value = 3650, message = "流水保留天数不能超过 3650")
    private int dailyFileRetentionDays;

    /**
     * 归档目录（memory/archive/）保留天数
     */
    @Min(value = 1, message = "归档保留天数必须大于 0")
    @Max(value = 3650, message = "归档保留天数不能超过 3650")
    private int archiveRetentionDays;

    /**
     * 会话日志保留天数
     */
    @Min(value = 1, message = "会话保留天数必须大于 0")
    @Max(value = 3650, message = "会话保留天数不能超过 3650")
    private int sessionRetentionDays;

    /**
     * 事实抽取触发策略：always 每轮抽取 / throttled 按间隔节流 / never 不自动抽取
     */
    @NotBlank(message = "事实抽取策略不能为空")
    @Pattern(regexp = "^(always|throttled|never)$", message = "事实抽取策略只能是 always / throttled / never")
    private String flushMode;

    /**
     * 事实抽取节流间隔（分钟），仅 {@code flushMode=throttled} 时生效
     */
    @Min(value = 1, message = "抽取节流间隔必须大于 0")
    @Max(value = 1440, message = "抽取节流间隔不能超过 1440 分钟")
    private int flushThrottleMinutes;

}