package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 智能体 × 工具 × 用户的信任决策对象 ai_agent_tool_trust
 *
 * <p>用户在对话确认卡片选择「记住该工具」时沉淀的免确认决策。与 {@link AiAgentTool} 分工不同：
 * 后者是管理端在定义页配置的工具授权（保存时先删后插，工具编码必须存在于业务工具注册表），
 * 本表是运行期由人工确认沉淀的决策，且必须覆盖外部 MCP 等「平台级、不在注册表内」的工具。
 * **维度含用户**：信任只对「记住该工具」的那个用户生效，同一智能体下其他用户仍会弹确认。
 * 撤销即物理删除该行。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent_tool_trust")
public class AiAgentToolTrust extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 信任决策：允许直接执行
     */
    public static final String DECISION_ALLOW = "0";

    /**
     * 信任决策：需人工确认（HITL）
     */
    public static final String DECISION_ASK = "1";

    /**
     * 信任决策：禁止调用
     */
    public static final String DECISION_DENY = "2";

    /**
     * 决策来源：人工确认时「记住该工具」
     */
    public static final String SOURCE_REMEMBER = "remember";

    /**
     * 决策来源：管理端手工维护
     */
    public static final String SOURCE_MANUAL = "manual";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 智能体主键
     */
    private Long agentId;

    /**
     * 工具编码（业务工具编码或外部 MCP 工具名）
     */
    private String toolCode;

    /**
     * 记住该工具的用户主键（信任只对该用户生效）
     */
    private Long userId;

    /**
     * 信任决策（0允许 1需确认 2禁止；当前仅产生 0）
     */
    private String decision;

    /**
     * 决策来源（remember / manual）
     */
    private String source;

    /**
     * 备注
     */
    private String remark;

}
