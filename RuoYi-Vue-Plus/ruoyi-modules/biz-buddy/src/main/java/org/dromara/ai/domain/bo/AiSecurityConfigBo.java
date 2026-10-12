package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安全合规配置业务对象（脱敏 + 内容安全）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.security.*}，页面保存后写入 Redis 覆盖默认值，
 * 下一次对话 / 编排即按新策略生效。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiSecurityConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容安全审核总开关（默认关闭；开启后按 action 处置）
     */
    private boolean enabled;

    /**
     * 审核引擎：local（本地词库）/ external（外部内容安全 API，预留）
     */
    @Pattern(regexp = "^(local|external)$", message = "审核引擎只能是 local 或 external")
    private String engine = "local";

    /**
     * 命中处置动作：log（只记录）/ mask（脱敏放行）/ block（阻断）
     */
    @Pattern(regexp = "^(log|mask|block)$", message = "命中动作只能是 log / mask / block")
    private String action = "log";

    /**
     * 是否审核用户输入
     */
    private boolean inputCheck = true;

    /**
     * 是否审核模型输出
     */
    private boolean outputCheck = true;

    /**
     * 是否审核渠道出站回复
     */
    private boolean channelOutCheck = true;

    /**
     * 是否审核工具结果（落库留痕与前端下发）
     */
    private boolean toolResultMask = true;

    /**
     * 是否审核知识库入库内容（切片送向量库前，BLOCK 则拒绝入库）
     */
    private boolean kbIngestCheck = true;

    /**
     * 是否审核 MCP 出口入参 / 出参
     */
    private boolean mcpOutCheck = true;

    /**
     * 是否审核「结构化决策出境」（外呼决策模型的 {@code state}；PII 脱敏为强制项、不在此开关内）
     */
    private boolean jevOutboundCheck = true;

    /**
     * 是否启用提示词注入防护（system prompt 加固 + 外部内容边界标记）
     */
    private boolean promptHardening = true;

    /**
     * 是否做 PII 脱敏（独立于内容审核开关，默认开启）
     */
    private boolean maskPii = true;

    /**
     * 流式输出的尾部缓冲字符数（防跨片切断）
     */
    @Min(value = 0, message = "尾部缓冲字符数不能为负")
    @Max(value = 2000, message = "尾部缓冲字符数过大")
    private int holdBackChars = 32;

    /**
     * 安全事件留痕中的命中片段最大字符数
     */
    @Min(value = 10, message = "命中片段长度至少 10")
    @Max(value = 2000, message = "命中片段长度过大")
    private int excerptChars = 200;

    /**
     * 外部审核引擎服务地址（engine=external 时使用；本轮仅占位）
     */
    private String providerEndpoint = "";

    /**
     * 外部审核引擎访问密钥（engine=external 时使用；本轮仅占位）
     */
    private String providerApiKey = "";

}