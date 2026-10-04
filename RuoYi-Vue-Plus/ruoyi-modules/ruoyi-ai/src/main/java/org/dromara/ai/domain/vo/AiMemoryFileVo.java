package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 长期记忆文件视图对象（REQ-AI-09）。
 *
 * <p>记忆按用户隔离存放于智能体工作区：{@code MEMORY.md} 为合并后的长期记忆，
 * {@code memory/YYYY-MM-DD.md} 为当日事实流水（只追加）。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiMemoryFileVo implements Serializable {

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
     * 文件类型（MEMORY 长期记忆 / DAILY 每日流水）
     */
    private String type;

    /**
     * 日期（每日流水取文件名，长期记忆为空）
     */
    private String date;

    /**
     * 字符数
     */
    private int chars;

}