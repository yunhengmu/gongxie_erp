package org.dromara.ai.core.safety;

/**
 * 安全合规校验的场景（决定用哪套开关与留痕口径）。
 *
 * @author BizBuddy
 */
public enum SafetyScene {

    /**
     * 用户输入（实时对话 / 无人值守 / 渠道入站）
     */
    INPUT("input", "用户输入"),

    /**
     * 模型输出（流式下发与落库）
     */
    OUTPUT("output", "模型输出"),

    /**
     * 渠道出站回复（对外发布，合规红线）
     */
    CHANNEL_OUT("channel_out", "渠道出站"),

    /**
     * 工具结果（落库留痕与前端下发；框架内部回填模型的环节平台不可见）
     */
    TOOL_RESULT("tool_result", "工具结果"),

    /**
     * 知识库入库（切片送向量库前）
     */
    KB_INGEST("kb_ingest", "知识库入库"),

    /**
     * MCP 出口（对外暴露给第三方 AI 的只读工具：入参 / 出参）
     */
    MCP_OUT("mcp_out", "MCP 出口");

    private final String code;

    private final String label;

    SafetyScene(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * @return 落库用的稳定编码
     */
    public String code() {
        return code;
    }

    /**
     * @return 中文名（留痕展示用）
     */
    public String label() {
        return label;
    }

}