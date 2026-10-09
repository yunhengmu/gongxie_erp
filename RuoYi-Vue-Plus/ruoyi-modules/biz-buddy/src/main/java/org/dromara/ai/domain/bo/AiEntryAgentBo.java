package org.dromara.ai.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.ai.domain.AiEntryAgent;

import java.io.Serial;

/**
 * 入口智能体业务对象 ai_entry_agent（REQ-AI-33）。
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = AiEntryAgent.class, reverseConvertGenerate = false)
public class AiEntryAgentBo extends AiAgentBo {

    @Serial
    private static final long serialVersionUID = 1L;

}
