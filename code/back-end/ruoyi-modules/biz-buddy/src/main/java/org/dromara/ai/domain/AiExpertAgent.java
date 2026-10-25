package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * AI 专家智能体对象 ai_expert_agent（REQ-AI-33）。
 *
 * <p>可被入口智能体路由的候选。新增 {@code auto_route}（是否自动路由）：
 * {@link #AUTO_ROUTE_ON} 的专家进入入口的**自动候选清单**；
 * {@link #AUTO_ROUTE_OFF} 的专家**只能被用户手动指定**（仍可见于工作台「指定专家」下拉、仍可正常执行）。</p>
 *
 * <p>写工具开关 {@code expert_write_enabled} 已不再由定义页配置，恒为「允许」
 * （写操作仍受三档授权约束：「需确认」档走 HITL 确认卡片、「禁止」档不注册）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_expert_agent")
public class AiExpertAgent extends AiAgent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否自动路由：开启（进入入口智能体的自动候选清单）
     */
    public static final String AUTO_ROUTE_ON = "1";

    /**
     * 是否自动路由：关闭（只能被用户手动指定）
     */
    public static final String AUTO_ROUTE_OFF = "0";

    /**
     * 能力描述（注入入口智能体的路由提示词；为空回落 {@code description}）
     */
    private String capabilityDesc;

    /**
     * 专家是否允许使用写工具（0 不允许＝专家强制只读；1 允许按「允许」档生效）。
     * 定义页无此开关，服务端保存时恒置 {@link AiAgent#WRITE_ENABLED}。
     */
    private String expertWriteEnabled;

    /**
     * 是否自动路由（1 进入口智能体的自动候选清单；0 只能被用户手动指定）
     */
    private String autoRoute;

    @Override
    public boolean expertWriteAllowed() {
        return WRITE_ENABLED.equals(expertWriteEnabled);
    }

}
