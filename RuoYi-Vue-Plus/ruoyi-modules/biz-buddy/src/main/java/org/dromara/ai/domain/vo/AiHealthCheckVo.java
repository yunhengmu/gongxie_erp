package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台健康自检单项结果（REQ-AI-35）。
 *
 * <p>每项由体检服务本地判定给出 {@link #status}：{@code PASS} 可用 / {@code WARN} 有隐患 /
 * {@code FAIL} 该能力当前不可用 / {@code UNKNOWN} 本地判定无法得出结论（需点「测试」做真实连接）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiHealthCheckVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 体检通过
     */
    public static final String STATUS_PASS = "PASS";

    /**
     * 有告警（不影响主流程，但建议处理）
     */
    public static final String STATUS_WARN = "WARN";

    /**
     * 体检失败（该能力当前不可用）
     */
    public static final String STATUS_FAIL = "FAIL";

    /**
     * 待探测（本地判定无结论，需点「测试」）
     */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * 探测目标：模型连通性
     */
    public static final String TARGET_MODEL = "model";

    /**
     * 探测目标：Milvus 连接
     */
    public static final String TARGET_MILVUS = "milvus";

    /**
     * 探测目标：外部 MCP 连接
     */
    public static final String TARGET_MCP_CLIENT = "mcp-client";

    /**
     * 探测目标：结构化决策（决策模型）连通性
     */
    public static final String TARGET_JEV = "jev";

    /**
     * 体检项标识（稳定不变，供前端定位）
     */
    private String key;

    /**
     * 体检项名称
     */
    private String name;

    /**
     * 分类（基础设施 / 模型与向量 / 集成 / 安全与运行时 / 智能体 / 存储）
     */
    private String category;

    /**
     * 状态：PASS / WARN / FAIL / UNKNOWN
     */
    private String status;

    /**
     * 判定说明（给出实际观测到的值）
     */
    private String detail;

    /**
     * 修复建议（通过时为空）
     */
    private String suggestion;

    /**
     * 是否支持「测试」做真实连接探测（本地判定不出的项为 true）
     */
    private boolean probeSupported;

    /**
     * 探测目标（probeSupported=true 时有效）
     */
    private String probeTarget;

    /**
     * 探测对象主键（模型 / MCP 连接；Milvus 无主键时为空）
     */
    private Long probeId;

    /**
     * 探测耗时（毫秒；仅探测结果有值）
     */
    private Long elapsedMs;

}
