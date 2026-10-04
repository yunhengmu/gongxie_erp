package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长期记忆文件内容视图对象（REQ-AI-09）。
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryContentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 工作区相对路径
     */
    private String path;

    /**
     * 文件正文（不存在时为空串）
     */
    private String content;

}