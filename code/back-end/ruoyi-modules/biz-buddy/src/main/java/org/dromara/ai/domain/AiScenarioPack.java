package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 场景包定义对象 ai_scenario_pack
 *
 * <p>场景包按角色 / 部门把多个**业务工具 + 外部 MCP 工具 + 技能**打包，用户在创建会话时选用，
 * 包内能力**叠加**到智能体既有绑定之上以增强对话（如「财务场景包」「人力场景包」）。</p>
 *
 * <p>三张关联表（{@code ai_scenario_pack_tool} / {@code ai_scenario_pack_mcp_tool} /
 * {@code ai_scenario_pack_skill}）保存时「先删后插」，故不设 {@code del_flag}。本表为逻辑删除：
 * 历史会话仍引用 {@code ai_session.scenario_pack_id}，行需保留（编码唯一索引带 {@code where del_flag='0'}）。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_scenario_pack")
public class AiScenarioPack extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生效范围：公开
     */
    public static final String SCOPE_ALL = "0";

    /**
     * 生效范围：本部门
     */
    public static final String SCOPE_DEPT = "1";

    /**
     * 生效范围：仅本人
     */
    public static final String SCOPE_SELF = "2";

    /**
     * 状态：启用
     */
    public static final String STATUS_ENABLED = "0";

    /**
     * 状态：停用
     */
    public static final String STATUS_DISABLED = "1";

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 场景包编码（唯一，创建后不可修改）
     */
    private String packCode;

    /**
     * 场景包名称（如「财务场景包」「人力场景包」）
     */
    private String packName;

    /**
     * 用途说明（创建任务时供用户判断该选哪个包）
     */
    private String description;

    /**
     * 生效范围（0 公开 / 1 本部门 / 2 仅本人）
     */
    private String visibleScope;

    /**
     * 归属部门（visible_scope=1 时比较该列）
     */
    private Long ownerDeptId;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

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