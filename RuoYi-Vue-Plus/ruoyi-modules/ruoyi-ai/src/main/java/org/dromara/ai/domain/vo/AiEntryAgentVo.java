package org.dromara.ai.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.ai.domain.AiEntryAgent;

import java.io.Serial;

/**
 * 入口智能体视图对象 ai_entry_agent（REQ-AI-33）。
 *
 * <p>全平台唯一（小Z），承载公共字段 + 单例行标记 {@code singleton}。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = AiEntryAgent.class)
public class AiEntryAgentVo extends AiAgentVo {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 单例行标记（恒 '1'，配合唯一索引强制「入口智能体全平台唯一」）
     */
    private String singleton;

}
