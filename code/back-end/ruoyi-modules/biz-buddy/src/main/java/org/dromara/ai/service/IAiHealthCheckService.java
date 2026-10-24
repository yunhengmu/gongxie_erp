package org.dromara.ai.service;

import org.dromara.ai.domain.bo.AiHealthProbeBo;
import org.dromara.ai.domain.vo.AiHealthCheckVo;
import org.dromara.ai.domain.vo.AiHealthReportVo;

/**
 * 平台健康自检 Service 接口（REQ-AI-35）。
 *
 * <p>把「开箱第一公里」与日常运维自查收敛为一屏：密钥是否配齐、向量维度是否一致、Milvus / MCP
 * 是否可用、时区是否错位、出厂密钥是否未收口等。**只读**——不提供一键修复。</p>
 *
 * @author BizBuddy
 */
public interface IAiHealthCheckService {

    /**
     * 执行一次全量自检。
     *
     * <p><b>纯本地判定，绝不发起外网调用</b>；需要真实连接的项返回
     * {@code UNKNOWN}（并标记支持探测），由 {@link #probe(AiHealthProbeBo)} 按需验证。</p>
     *
     * @return 体检报告
     */
    AiHealthReportVo check();

    /**
     * 对单个体检项做真实连接探测（会发起外网 / 建连）。
     *
     * @param bo 探测目标与对象主键
     * @return 该项的探测结果（PASS / FAIL）
     */
    AiHealthCheckVo probe(AiHealthProbeBo bo);

}
