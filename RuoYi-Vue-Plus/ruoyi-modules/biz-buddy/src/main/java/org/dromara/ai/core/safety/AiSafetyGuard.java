package org.dromara.ai.core.safety;

import lombok.extern.slf4j.Slf4j;
import org.dromara.ai.core.agent.AiRuntimeConfigStore;
import org.dromara.ai.domain.bo.AiSecurityConfigBo;
import org.dromara.ai.util.AiPiiMasker;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 安全合规统一守卫：把「内容安全审核」与「PII 脱敏」合成一次调用，供链路各出口使用。
 *
 * <p>处置顺序固定：先内容审核（受 {@code enabled} + 场景开关控制），再 PII 脱敏（受 {@code maskPii} 控制，
 * 与之独立）。返回 {@link SafetyDecision}：{@code blocked()} 为真时须阻断；否则以 {@code text()} 放行。</p>
 *
 * <p><b>审核引擎按运行期配置路由</b>：本地词库（{@link LocalKeywordSafetyEngine}）与外部内容安全 API
 * （{@link ExternalApiSafetyEngine}）两个实现按 {@code engine} 选择；配置的引擎不可用（外部引擎未配端点 /
 * 密钥，或引擎名未接入）时<b>降级为本地词库</b>并告警，降级告警按引擎 + 端点去重（避免流式逐片刷屏）。</p>
 *
 * <p>留痕（{@code ai_safety_event}）由各出口在拿到决策后自行调用
 * {@code IAiSafetyEventService#record}，因为那里才持有会话 / 消息 / 用户上下文。</p>
 *
 * @author BizBuddy
 */
@Slf4j
@Component
public class AiSafetyGuard {

    /**
     * 命中词替换后的掩码
     */
    private static final String WORD_MASK = "***";

    /**
     * 本地词库引擎名（降级兜底）
     */
    private static final String ENGINE_LOCAL = "local";

    /**
     * 命中阻断时的统一拒答文案（输入 / 输出共用）
     */
    public static final String REFUSAL_TEXT = "抱歉，该内容不符合平台安全规范，已拦截。";

    private final AiRuntimeConfigStore runtimeConfigStore;

    /**
     * 已接入的审核引擎：引擎名（小写）→ 实现
     */
    private final Map<String, IAiContentSafetyEngine> engines;

    /**
     * 降级兜底引擎（本地词库）；缺失时为 {@code null}
     */
    private final IAiContentSafetyEngine fallbackEngine;

    /**
     * 已告警的降级指纹，避免流式逐片重复告警
     */
    private volatile String degradedWarningKey;

    /**
     * @param runtimeConfigStore 运行期配置中心
     * @param engines            已接入的审核引擎（按 {@link IAiContentSafetyEngine#engineName()} 索引）
     */
    public AiSafetyGuard(AiRuntimeConfigStore runtimeConfigStore, List<IAiContentSafetyEngine> engines) {
        this.runtimeConfigStore = runtimeConfigStore;
        Map<String, IAiContentSafetyEngine> byName = new LinkedHashMap<>();
        for (IAiContentSafetyEngine engine : engines) {
            if (engine != null && StringUtils.isNotBlank(engine.engineName())) {
                byName.put(engine.engineName().trim().toLowerCase(Locale.ROOT), engine);
            }
        }
        this.engines = Map.copyOf(byName);
        this.fallbackEngine = byName.get(ENGINE_LOCAL);
    }

    /**
     * 配置快照缓存时长（毫秒）：流式逐片调用不能每次都读 Redis
     */
    private static final long CONFIG_TTL_MS = 2000L;

    /**
     * 配置快照（短 TTL；保存后最多 2 秒生效，符合「下一次对话生效」的契约）
     */
    private volatile AiSecurityConfigBo cachedConfig;

    private volatile long cachedAt;

    /**
     * 取当前生效的安全配置（带短 TTL 缓存）。
     *
     * @return 安全配置
     */
    private AiSecurityConfigBo config() {
        long now = System.currentTimeMillis();
        AiSecurityConfigBo local = cachedConfig;
        if (local != null && now - cachedAt < CONFIG_TTL_MS) {
            return local;
        }
        local = runtimeConfigStore.security();
        cachedConfig = local;
        cachedAt = now;
        return local;
    }

    /**
     * @return 是否有任何安全能力生效（内容审核开启 或 PII 脱敏开启）；为 false 时出口可走快速路径
     */
    public boolean anyActive() {
        AiSecurityConfigBo cfg = config();
        return cfg.isEnabled() || cfg.isMaskPii();
    }

    /**
     * @return 流式输出的尾部缓冲字符数（防跨片切断）
     */
    public int holdBackChars() {
        return config().getHoldBackChars();
    }

    /**
     * @return 当前**实际生效**的引擎名（留痕用）；降级时返回兜底引擎名（{@code local}）
     */
    public String engineName() {
        AiSecurityConfigBo cfg = config();
        IAiContentSafetyEngine engine = resolve(cfg);
        return engine == null ? cfg.getEngine() : engine.engineName();
    }

    /**
     * 按运行期配置解析实际生效的引擎：配置的引擎未接入或不可用时降级为本地词库并告警（告警去重）。
     *
     * @param cfg 配置快照
     * @return 实际生效引擎；无任何可用引擎时为 {@code null}
     */
    private IAiContentSafetyEngine resolve(AiSecurityConfigBo cfg) {
        String configured = StringUtils.trim(cfg.getEngine());
        String key = configured == null ? "" : configured.toLowerCase(Locale.ROOT);
        IAiContentSafetyEngine engine = engines.get(key);
        if (engine == null) {
            warnDegradeOnce("absent|" + key,
                "审核引擎未接入（engine={}），已降级为本地词库", configured);
            return fallbackEngine;
        }
        if (!engine.available(cfg)) {
            warnDegradeOnce("unavailable|" + key + "|" + StringUtils.trim(cfg.getProviderEndpoint()),
                "审核引擎不可用（engine={}，端点或密钥缺失），已降级为本地词库", configured);
            return fallbackEngine;
        }
        return engine;
    }

    /**
     * 降级告警（同一指纹只告警一次；引擎 / 配置变更后重新告警）。
     *
     * @param fingerprint 告警指纹
     * @param format      日志模板
     * @param arg         模板参数
     */
    private void warnDegradeOnce(String fingerprint, String format, Object arg) {
        if (fingerprint.equals(degradedWarningKey)) {
            return;
        }
        degradedWarningKey = fingerprint;
        log.warn(format, arg);
    }

    /**
     * 对一段文本执行安全守卫。
     *
     * @param text  原文（可空）
     * @param scene 场景
     * @return 处置结果
     */
    public SafetyDecision guard(String text, SafetyScene scene) {
        if (text == null || text.isEmpty()) {
            return SafetyDecision.pass(text);
        }
        AiSecurityConfigBo cfg = config();
        String working = text;
        boolean hit = false;
        List<String> hits = new ArrayList<>();
        if (cfg.isEnabled() && sceneEnabled(cfg, scene)) {
            IAiContentSafetyEngine engine = resolve(cfg);
            ContentSafetyResult result = engine == null
                ? ContentSafetyResult.miss() : engine.check(working, scene, cfg);
            if (result.hit()) {
                hit = true;
                hits.addAll(result.hits());
                SafetyAction action = SafetyAction.parse(cfg.getAction());
                if (action == SafetyAction.BLOCK) {
                    return SafetyDecision.block(hits);
                }
                if (action == SafetyAction.MASK) {
                    working = maskWords(working, result.hits());
                }
            }
        }
        if (cfg.isMaskPii() && scene != SafetyScene.INPUT) {
            AiPiiMasker.Outcome outcome = AiPiiMasker.mask(working);
            if (outcome.count() > 0) {
                hit = true;
                hits.addAll(outcome.types());
                working = outcome.text();
            }
        }
        return hit ? new SafetyDecision(SafetyAction.MASK, working, true, hits) : SafetyDecision.pass(text);
    }

    /**
     * 只脱敏不阻断：供「工具结果落库 / 下发」等不允许阻断的场景使用。
     *
     * @param text  原文（可空）
     * @param scene 场景
     * @return 脱敏后的文本；命中阻断动作时也按脱敏即原文放行（不阻断）
     */
    public String mask(String text, SafetyScene scene) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        SafetyDecision decision = guard(text, scene);
        return decision.blocked() ? text : decision.text();
    }

    /**
     * 命中的敏感词就地替换为掩码。
     *
     * @param text 文本
     * @param hits 命中词
     * @return 替换后的文本
     */
    private String maskWords(String text, List<String> hits) {
        String result = text;
        for (String hit : hits) {
            if (StringUtils.isNotBlank(hit)) {
                result = result.replace(hit, WORD_MASK);
            }
        }
        return result;
    }

    /**
     * 场景是否启用审核。
     *
     * @param cfg   配置
     * @param scene 场景
     * @return 是否启用
     */
    private boolean sceneEnabled(AiSecurityConfigBo cfg, SafetyScene scene) {
        return switch (scene) {
            case INPUT -> cfg.isInputCheck();
            case OUTPUT -> cfg.isOutputCheck();
            case CHANNEL_OUT -> cfg.isChannelOutCheck();
            case TOOL_RESULT -> cfg.isToolResultMask();
            case KB_INGEST -> cfg.isKbIngestCheck();
            case MCP_OUT -> cfg.isMcpOutCheck();
            case JEV_OUTBOUND -> cfg.isJevOutboundCheck();
        };
    }

}