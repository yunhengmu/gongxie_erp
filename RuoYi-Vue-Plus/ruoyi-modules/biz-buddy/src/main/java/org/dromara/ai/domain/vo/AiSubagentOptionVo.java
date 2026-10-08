package org.dromara.ai.domain.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.ai.domain.AiSubagent;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 子智能体选项视图对象（供智能体定义页选择绑定子智能体）
 *
 * @author BizBuddy
 */
@Data
@AutoMapper(target = AiSubagent.class)
public class AiSubagentOptionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 子智能体标识
     */
    private String name;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 工作区模式（ISOLATED 隔离 / SHARED 共享）
     */
    private String workspaceMode;

    /**
     * 单次最大推理迭代次数
     */
    private Integer steps;

    /**
     * 工具白名单（落库的 JSON 数组文本，与实体字段同名以便转换）
     */
    private String toolCodes;

    /**
     * 工具白名单（附加字段，由 Service 从 toolCodes JSON 解析）
     */
    @TableField(exist = false)
    private List<String> toolCodeList;

}