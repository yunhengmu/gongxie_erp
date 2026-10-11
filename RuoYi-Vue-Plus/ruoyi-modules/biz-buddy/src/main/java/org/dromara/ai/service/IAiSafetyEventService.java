package org.dromara.ai.service;

import org.dromara.ai.core.safety.SafetyDecision;
import org.dromara.ai.core.safety.SafetyScene;
import org.dromara.ai.domain.bo.AiSafetyEventBo;
import org.dromara.ai.domain.vo.AiSafetyEventVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * AI 安全事件 Service 接口
 *
 * <p>写入侧在安全校验链路内按命中留痕（追加型明细，留痕失败不影响主流程）；
 * 读取侧供管理面「安全事件」页检索。</p>
 *
 * @author BizBuddy
 */
public interface IAiSafetyEventService {

    /**
     * 记录一次安全事件（敏感词 / PII 命中留痕）。
     *
     * <p>追加型留痕，异常只告警不抛出，避免影响对话主流程。</p>
     *
     * @param scene     场景
     * @param decision  处置结果
     * @param engine    命中引擎（word 敏感词 / pii 隐私信息）
     * @param sessionId 会话主键
     * @param messageId 助手消息主键
     * @param agentId   智能体主键
     * @param userId    触发用户主键
     * @param excerpt   触发文本（落库时截断至 200 字符）
     */
    void record(SafetyScene scene, SafetyDecision decision, String engine,
                Long sessionId, Long messageId, Long agentId, Long userId, String excerpt);

    /**
     * 分页查询安全事件。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiSafetyEventVo> queryPage(AiSafetyEventBo bo, PageQuery pageQuery);

    /**
     * 查询安全事件详情。
     *
     * @param id 主键
     * @return 视图对象
     */
    AiSafetyEventVo queryById(Long id);

}