package org.dromara.ai.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 首登检查清单。
 *
 * <p>完成状态全部由服务端现有数据实时推导（健康自检 / 文档状态 / 用户消息），
 * 不使用前端本地标记，故换浏览器、刷新后结论一致。</p>
 *
 * @author BizBuddy
 */
@Data
public class AiOnboardingReportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 清单项
     */
    private List<AiOnboardingItemVo> items;

    /**
     * 必做项总数
     */
    private int requiredCount;

    /**
     * 已完成项数
     */
    private int doneCount;

    /**
     * 是否全部完成
     */
    private boolean allDone;

}
