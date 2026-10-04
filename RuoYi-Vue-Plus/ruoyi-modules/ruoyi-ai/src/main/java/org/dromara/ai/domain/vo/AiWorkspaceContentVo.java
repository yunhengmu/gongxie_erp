package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工作区文件内容视图对象（REQ-AI-08）。
 *
 * @author BizBuddy
 */
@Data
public class AiWorkspaceContentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作区相对路径
     */
    private String path;

    /**
     * 层来源（SHARED 共享默认 / USER 用户覆盖）
     */
    private String scope;

    /**
     * 文件正文（该层不存在时为空串）
     */
    private String content;

}