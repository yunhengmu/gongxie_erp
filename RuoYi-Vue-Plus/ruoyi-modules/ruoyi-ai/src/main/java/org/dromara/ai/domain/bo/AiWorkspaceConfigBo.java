package org.dromara.ai.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区上下文配置业务对象（REQ-AI-08）。
 *
 * <p>全平台运行期配置：默认值取自 {@code bizbuddy.ai.chat.*}，页面保存后写入 Redis 覆盖默认值，
 * 并参与智能体装配指纹判定，保存后下一次对话即按新策略装配生效。</p>
 *
 * <p>工作区文件本身按用户覆盖（AgentScope 默认 {@code IsolationScope.USER} 的两层读）：
 * 工作区根为共享默认，用户目录为按用户覆盖；本对象只描述「注入开关与附加上下文文件清单」，
 * 不含具体文件内容。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiWorkspaceConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用工作区上下文注入（关闭时不再注入 AGENTS.md 与附加上下文文件）
     */
    private boolean enabled;

    /**
     * 附加上下文文件清单（工作区相对路径，与 AGENTS.md 一样全文注入 system prompt）
     */
    private List<String> additionalContextFiles = new ArrayList<>();

}