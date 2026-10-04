package org.dromara.ai.mapper;

import org.dromara.ai.domain.AiAgentToolTrust;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * AI 智能体工具信任决策 Mapper 接口
 *
 * <p>信任决策无独立实体转换需求，VO 泛型取实体自身；查询结果由 Service 侧转换为
 * {@link org.dromara.ai.domain.vo.AiAgentToolTrustVo}。</p>
 *
 * @author BizBuddy
 */
public interface AiAgentToolTrustMapper extends BaseMapperPlus<AiAgentToolTrust, AiAgentToolTrust> {

}
