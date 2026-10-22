package org.dromara.ai.mapper;

import org.apache.ibatis.annotations.Select;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * AI 会话 Mapper 接口
 *
 * @author BizBuddy
 */
public interface AiSessionMapper extends BaseMapperPlus<AiSession, AiSessionVo> {

    /**
     * 查询存在过会话历史的用户主键（长期记忆自动归档按用户维度遍历）。
     *
     * <p>不过滤逻辑删除：会话删除后长期记忆仍留在工作区，不能因为会话被删就停止维护。</p>
     *
     * @return 用户主键列表
     */
    @Select("select distinct user_id from ai_session where user_id is not null")
    List<Long> selectDistinctUserIds();

}
