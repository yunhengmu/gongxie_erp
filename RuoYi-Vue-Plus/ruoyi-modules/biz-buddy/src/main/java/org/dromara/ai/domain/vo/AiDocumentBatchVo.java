package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 知识库文档批量提交回执
 *
 * <p>提交是异步的：此处只返回「已受理」与「被跳过」两类结果，
 * 实际处理进度由 {@link AiDocumentBatchStatusVo} 轮询。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiDocumentBatchVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 已受理（已入队）的文档主键
     */
    private List<Long> accepted = new ArrayList<>();

    /**
     * 被跳过的文档及原因（不存在、正在处理、前置条件不满足等）
     */
    private List<Skipped> skipped = new ArrayList<>();

    /**
     * 追加一条跳过记录。
     *
     * @param id      文档主键
     * @param docName 文档名称（文档不存在时为 null）
     * @param reason  跳过原因
     */
    public void addSkipped(Long id, String docName, String reason) {
        Skipped item = new Skipped();
        item.setId(id);
        item.setDocName(docName);
        item.setReason(reason);
        skipped.add(item);
    }

    /**
     * 跳过明细
     */
    @Data
    public static class Skipped implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 文档主键
         */
        private Long id;

        /**
         * 文档名称
         */
        private String docName;

        /**
         * 跳过原因
         */
        private String reason;

    }

}
