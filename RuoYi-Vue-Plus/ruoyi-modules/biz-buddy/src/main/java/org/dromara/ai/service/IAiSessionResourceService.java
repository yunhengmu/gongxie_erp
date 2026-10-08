package org.dromara.ai.service;

import org.dromara.ai.domain.AiScenarioPack;
import org.dromara.ai.domain.AiSession;
import org.dromara.ai.domain.bo.AiSessionResourceBatchBo;
import org.dromara.ai.domain.dto.AiSessionResourceSet;
import org.dromara.ai.domain.vo.AiSessionResourceVo;

import java.util.List;

/**
 * AI 会话级资源集 Service（REQ-AI-32）。
 *
 * <p>负责「会话选中资源」的读写与**展开**：把场景包 + 直接选中的业务工具 / 技能 / MCP 工具，
 * 聚合为装配层可直接消费的 {@link AiSessionResourceSet}（含身份签名与包内容指纹）。</p>
 *
 * @author BizBuddy
 */
public interface IAiSessionResourceService {

    /**
     * 读会话资源集并展开（每轮进对话时现读一次，含旧会话 {@code scenario_pack_id} 兼容读取）。
     *
     * @param session 会话实体
     * @return 已展开的资源集；无任何资源时返回空集
     */
    AiSessionResourceSet resolveForSession(AiSession session);

    /**
     * 由单个场景包构造资源集（无人值守链路 / 兼容路径使用）。
     *
     * @param pack 场景包（已确认启用；可空）
     * @return 只含该场景包的资源集；pack 为空时返回空集
     */
    AiSessionResourceSet ofSinglePack(AiScenarioPack pack);

    /**
     * 读会话资源集（含展示名，供标签 chip 渲染）。
     *
     * @param sessionId 会话主键
     * @return 资源项列表（按资源类型 + 主键排序）
     */
    List<AiSessionResourceVo> queryResources(Long sessionId);

    /**
     * 批量增（重复项幂等：已存在的活跃行跳过）。
     *
     * @param sessionId 会话主键
     * @param bo        资源项集合
     * @return 是否全部处理成功
     */
    Boolean addResources(Long sessionId, AiSessionResourceBatchBo bo);

    /**
     * 批量删（逻辑删除；{@code scenario} 行删除**不级联**删除已展开的单个资源）。
     *
     * @param sessionId 会话主键
     * @param bo        资源项集合
     * @return 是否全部处理成功
     */
    Boolean removeResources(Long sessionId, AiSessionResourceBatchBo bo);

}