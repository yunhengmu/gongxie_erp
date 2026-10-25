package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作区文件视图对象（REQ-AI-08）。
 *
 * <p>工作区文件按用户覆盖：工作区根为「共享默认」，用户目录（{@code <userId>/}）为「按用户覆盖」，
 * 生效内容取用户覆盖优先、共享默认为兜底。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiWorkspaceFileVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作区相对路径
     */
    private String path;

    /**
     * 文件名
     */
    private String name;

    /**
     * 文件类型（AGENTS 人格与行为约定 / EXTRA 附加上下文文件）
     */
    private String type;

    /**
     * 是否存在共享默认（工作区根）
     */
    private boolean hasShared;

    /**
     * 共享默认字符数
     */
    private int sharedChars;

    /**
     * 是否存在用户覆盖（用户目录）
     */
    private boolean hasUser;

    /**
     * 用户覆盖字符数
     */
    private int userChars;

    /**
     * 生效来源（USER 用户覆盖生效 / SHARED 共享默认生效 / NONE 两处均无内容）
     */
    private String effective;

}