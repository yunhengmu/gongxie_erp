package org.dromara.ai.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 业务工具标签定义对象 ai_tool_tag
 *
 * <p>标签用于给业务工具做分类与检索（一个工具可打多个标签，按标签查工具为「任一命中」语义）。
 * 标签**仅用于管理面分类**，不参与智能体装配，也不影响工具的生效集合。</p>
 *
 * @author BizBuddy
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool_tag")
public class AiToolTag extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 名称最大长度（与库列宽一致）
     */
    public static final int TAG_NAME_MAX_LENGTH = 50;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 标签名称（唯一）
     */
    private String tagName;

    /**
     * 备注（用途说明）
     */
    private String remark;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

}