package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * AI 入口智能体对象 ai_entry_agent（REQ-AI-33）。
 *
 * <p><b>全平台唯一，恒 1 行</b>（小Z）：由库层的 {@code singleton} 列 + 唯一索引
 * {@code uk_ai_entry_agent_singleton} 强制，替代已退役的 {@code uk_ai_agent_entry}。
 * 入口是路由决策的发起方，装配时注册 {@code route_decision} 工具与候选专家清单中间件。</p>
 *
 * <p>入口不绑定业务工具 / 知识库 / 技能 / 子智能体（能力由会话资源集提供），
 * 故没有 {@code ai_agent_tool} 等绑定关系参与其配置。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_entry_agent")
public class AiEntryAgent extends AiAgent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 单例行标记（恒 '1'，配合唯一索引强制「入口智能体全平台唯一」）
     */
    private String singleton;

    @Override
    public boolean isEntry() {
        return true;
    }

    @Override
    public String role() {
        return ROLE_ENTRY;
    }

}
