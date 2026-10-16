package org.dromara.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安全合规配置视图对象（脱敏 + 内容安全）。
 *
 * <p>与 {@code AiSecurityConfigBo} 的策略字段一一对应。外部审核引擎的访问密钥**绝不下发前端**——
 * 只回带掩码 {@link #providerApiKeyMask}；页面保存时密钥留空表示不修改，由服务端沿用已存值。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiSecurityConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容安全审核总开关（默认关闭；开启后按 action 处置）
     */
    private boolean enabled;

    /**
     * 审核引擎：local（本地词库）/ external（外部内容安全 API，未配端点或密钥时降级为本地词库）
     */
    private String engine;

    /**
     * 命中处置动作：log（只记录）/ mask（脱敏放行）/ block（阻断）
     */
    private String action;

    /**
     * 是否审核用户输入
     */
    private boolean inputCheck;

    /**
     * 是否审核模型输出
     */
    private boolean outputCheck;

    /**
     * 是否审核渠道出站回复
     */
    private boolean channelOutCheck;

    /**
     * 是否审核工具结果（落库留痕与前端下发）
     */
    private boolean toolResultMask;

    /**
     * 是否审核知识库入库内容（切片送向量库前，BLOCK 则拒绝入库）
     */
    private boolean kbIngestCheck;

    /**
     * 是否审核 MCP 出口入参 / 出参
     */
    private boolean mcpOutCheck;

    /**
     * 是否审核「结构化决策出境」（外呼决策模型的 state；PII 脱敏为强制项、不在此开关内）
     */
    private boolean jevOutboundCheck;

    /**
     * 是否启用提示词注入防护（system prompt 加固 + 外部内容边界标记）
     */
    private boolean promptHardening;

    /**
     * 是否做 PII 脱敏（独立于内容审核开关）
     */
    private boolean maskPii;

    /**
     * 流式输出的尾部缓冲字符数（防跨片切断）
     */
    private int holdBackChars;

    /**
     * 安全事件留痕中的命中片段最大字符数
     */
    private int excerptChars;

    /**
     * 外部审核引擎服务地址（engine=external 时使用；留空即降级为本地词库）
     */
    private String providerEndpoint;

    /**
     * 外部审核引擎访问密钥密文：仅用于服务端计算掩码，不参与序列化，绝不下发前端
     */
    @JsonIgnore
    private String providerApiKey;

    /**
     * 密钥掩码（已配置为 ******，未配置为空串）
     */
    private String providerApiKeyMask;

}
