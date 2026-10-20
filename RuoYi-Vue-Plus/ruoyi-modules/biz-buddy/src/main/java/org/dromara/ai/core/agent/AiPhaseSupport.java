package org.dromara.ai.core.agent;

import org.dromara.common.core.utils.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话期实时进度（{@code phase} 事件）的下发辅助。
 *
 * <p>一轮提问里，模型思考 / 工具执行 / 专家执行 / 整合汇总期间后端长时间不下发任何事件，
 * 前端助手气泡只剩一个闪烁光标，用户无法判断「还在跑」还是「卡死了」。本类把这些阶段
 * 归一为一行面向前端的状态文案，实时替换展示。</p>
 *
 * <p><b>与工具卡片的分工</b>：工具 / 专家卡片是**可追溯工件**（入参、结果、耗时），
 * 本类的状态行是**实时播报**。故专家直答链路即便 {@code forwardToolEvents=false}（不下发工具卡片），
 * 也照样下发「专家「X」正在调用「Y」工具…」——只透出**用了哪个工具**，入参与结果仍不下发。</p>
 *
 * <p><b>线程约束</b>：与 {@link AiSseSupport} 同口径，无状态、可直接在 Reactor 订阅线程调用。</p>
 *
 * @author BizBuddy
 */
final class AiPhaseSupport {

    /**
     * 事件类型：对话期实时进度
     */
    static final String EVENT_PHASE = "phase";

    /**
     * 阶段：判断该由谁回答（结构化决策闸门 / 入口轮首轮模型调用之前）
     */
    static final String PHASE_ROUTING = "routing";

    /**
     * 阶段：模型思考中
     */
    static final String PHASE_THINKING = "thinking";

    /**
     * 阶段：工具执行中
     */
    static final String PHASE_TOOL = "tool";

    /**
     * 阶段：专家执行中
     */
    static final String PHASE_EXPERT = "expert";

    /**
     * 阶段：人工确认后续跑
     */
    static final String PHASE_RESUMING = "resuming";

    /**
     * 阶段：整合专家结论
     */
    static final String PHASE_INTEGRATING = "integrating";

    /**
     * 阶段：整理最终回答
     */
    static final String PHASE_FINALIZING = "finalizing";

    private AiPhaseSupport() {
    }

    /**
     * 下发一条进度事件。
     *
     * <p>同一 {@code phase|text} 连续重复时跳过（一轮里模型调用会有多次，文案相同，无需刷屏）。</p>
     *
     * @param emitter    SSE 发射器；为 null（无人值守链路）时直接丢弃
     * @param state      流式累积状态；为 null 时丢弃
     * @param phase      阶段标识（见本类常量）
     * @param text       面向前端的中文文案（由后端拼全量，前端只渲染）
     * @param expertName 说话人专家名；为 null 表示入口智能体（小Z）
     */
    static void emit(SseEmitter emitter, AiStreamState state, String phase, String text, String expertName) {
        if (emitter == null || state == null || StringUtils.isBlank(text)) {
            return;
        }
        String key = phase + "|" + text;
        if (key.equals(state.lastPhaseKey)) {
            return;
        }
        state.lastPhaseKey = key;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", EVENT_PHASE);
        payload.put("phase", phase);
        payload.put("text", text);
        payload.put("speaker", StringUtils.isBlank(expertName) ? "entry" : "expert");
        if (StringUtils.isNotBlank(expertName)) {
            payload.put("expertName", expertName);
        }
        AiSseSupport.send(emitter, payload);
    }

    /**
     * 说话人前缀：入口智能体为「小Z 」，专家为「专家「X」」。
     *
     * @param state 流式累积状态
     * @return 文案前缀
     */
    static String who(AiStreamState state) {
        String expertName = state == null ? null : state.speakerExpertName;
        return StringUtils.isBlank(expertName) ? "小Z " : "专家「" + expertName + "」";
    }
}
