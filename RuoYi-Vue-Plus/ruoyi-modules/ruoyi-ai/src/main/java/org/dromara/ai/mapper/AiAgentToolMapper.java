package org.dromara.ai.mapper;

import org.dromara.ai.domain.AiAgentTool;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * AI 智能体工具绑定 Mapper 接口
 *
 * <p>绑定关系无独立视图对象，仅做直连 CRUD（按 agent_id 先删后插），
 * 故 VO 泛型取实体自身，不参与 VO 转换。</p>
 *
 * @author BizBuddy
 */
public interface AiAgentToolMapper extends BaseMapperPlus<AiAgentTool, AiAgentTool> {

}
