package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 会话级资源集对象 ai_session_resource（REQ-AI-32）
 *
 * <p>记录某会话选中的**场景包 / 业务工具 / 技能 / MCP 工具**。这份资源集在装配时**对入口智能体（小Z）
 * 与被执行的专家同时可见**（口径变更，取代原「专家 {@code pack=null}」）。</p>
 *
 * <p><b>叉号移除即逻辑删除</b>（{@code del_flag='1'}）；重新选中即新插一行。部分唯一索引
 * {@code (session_id, resource_type, resource_id, resource_key) where del_flag='0'} 保证重复点击不产生重复行。
 * 场景行删除**不级联**删除其已展开的单个资源（反之亦然，属有意为之）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_session_resource")
public class AiSessionResource extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型：场景包
     */
    public static final String TYPE_SCENARIO = "scenario";

    /**
     * 资源类型：业务工具
     */
    public static final String TYPE_TOOL = "tool";

    /**
     * 资源类型：技能
     */
    public static final String TYPE_SKILL = "skill";

    /**
     * 资源类型：MCP 工具
     */
    public static final String TYPE_MCP_TOOL = "mcp_tool";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 会话主键（ai_session.id）
     */
    private Long sessionId;

    /**
     * 资源类型（scenario / tool / skill / mcp_tool）
     */
    private String resourceType;

    /**
     * 资源主键（场景包→ai_scenario_pack.id；工具→ai_tool.id；技能→ai_skill.id；MCP 工具→ai_mcp_client.id）
     */
    private Long resourceId;

    /**
     * 稳定键（展示与去重）：场景包编码 / 工具编码 / 技能名 / MCP 远端工具名
     */
    private String resourceKey;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}