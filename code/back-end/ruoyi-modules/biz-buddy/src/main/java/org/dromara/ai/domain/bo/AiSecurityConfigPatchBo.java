package org.dromara.ai.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 安全合规配置的部分更新对象。
 *
 * <p>页面按「内容审核 / PII 脱敏」两个页签分别保存，每次只提交本页签字段；
 * 未提交（{@code null}）的字段沿用当前生效值。因此本对象与
 * {@link AiSecurityConfigBo} 的唯一差别是：所有字段使用包装类型，以便区分「未提交」与「显式关闭 / 置零」。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiSecurityConfigPatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 内容安全审核总开关（默认关闭；开启后按 action 处置）
     */
    private Boolean enabled;

    /**
     * 审核引擎：local（本地词库）/ external（外部内容安全 API，未配端点或密钥时降级为本地词库）
     */
    @Pattern(regexp = "^(local|external)$", message = "审核引擎只能是 local 或 external")
    private String engine;

    /**
     * 命中处置动作：log（只记录）/ mask（脱敏放行）/ block（阻断）
     */
    @Pattern(regexp = "^(log|mask|block)$", message = "命中动作只能是 log / mask / block")
    private String action;

    /**
     * 是否审核用户输入
     */
    private Boolean inputCheck;

    /**
     * 是否审核模型输出
     */
    private Boolean outputCheck;

    /**
     * 是否审核渠道出站回复
     */
    private Boolean channelOutCheck;

    /**
     * 是否审核工具结果（落库留痕与前端下发）
     */
    private Boolean toolResultMask;

    /**
     * 是否审核知识库入库内容（切片送向量库前，BLOCK 则拒绝入库）
     */
    private Boolean kbIngestCheck;

    /**
     * 是否审核 MCP 出口入参 / 出参
     */
    private Boolean mcpOutCheck;

    /**
     * 是否审核「结构化决策出境」（外呼决策模型的 {@code state}；PII 脱敏为强制项、不在此开关内）
     */
    private Boolean jevOutboundCheck;

    /**
     * 是否启用提示词注入防护（system prompt 加固 + 外部内容边界标记）
     */
    private Boolean promptHardening;

    /**
     * 是否做 PII 脱敏（独立于内容审核开关，默认开启）
     */
    private Boolean maskPii;

    /**
     * 流式输出的尾部缓冲字符数（防跨片切断）
     */
    @Min(value = 0, message = "尾部缓冲字符数不能为负")
    @Max(value = 2000, message = "尾部缓冲字符数过大")
    private Integer holdBackChars;

    /**
     * 安全事件留痕中的命中片段最大字符数
     */
    @Min(value = 10, message = "命中片段长度至少 10")
    @Max(value = 2000, message = "命中片段长度过大")
    private Integer excerptChars;

    /**
     * 外部审核引擎服务地址（engine=external 时使用；显式提交空串即清空并降级为本地词库）
     */
    private String providerEndpoint;

    /**
     * 外部审核引擎访问密钥（engine=external 时使用；留空或未提交均沿用已存值）
     */
    private String providerApiKey;

}
