package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 业务工具注册对象 ai_tool
 *
 * <p>本表只管理工具的注册元数据与启停，「实现」仍需代码侧存在
 * （{@code implName} 对应 {@code AiToolFactory} 中登记的 key）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool")
public class AiTool extends BaseEntity {

    /**
     * 工具类型：只读
     */
    public static final String TYPE_READ_ONLY = "0";

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 对外暴露：是（REQ-AI-15）
     */
    public static final String EXPOSED_YES = "1";

    /**
     * 对外暴露：否（REQ-AI-15）
     */
    public static final String EXPOSED_NO = "0";

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 工具编码（唯一，作为 AgentTool 的 name）
     */
    private String toolCode;

    /**
     * 工具显示名
     */
    private String toolName;

    /**
     * 用途说明（同时作为工具对模型暴露的 description）
     */
    private String description;

    /**
     * 工具类型（0只读 1写入）
     */
    private String toolType;

    /**
     * 参数定义（JSON Schema）
     */
    private String paramsSchema;

    /**
     * 代码侧实现标识（AiToolFactory 中的 key）
     */
    private String implName;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 是否对外暴露给 MCP 出口（REQ-AI-15：0否 1是；仅只读工具可勾选）
     */
    private String mcpExposed;

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
