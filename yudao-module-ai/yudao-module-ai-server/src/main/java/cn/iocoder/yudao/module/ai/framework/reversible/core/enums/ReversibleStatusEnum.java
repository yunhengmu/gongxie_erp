package cn.iocoder.yudao.module.ai.framework.reversible.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 可撤回账本帧的状态
 *
 * 状态迁移（单向，不可逆）：
 * <pre>
 *     POSTED --CAS--> REVERSING --冲账成功--> REVERSED
 *                              \--冲账失败--> FAILED
 * </pre>
 *
 * 只有 POSTED 可被冲账；REVERSING / REVERSED / FAILED 都不可再冲。
 *
 * @author yudao
 */
@Getter
@AllArgsConstructor
public enum ReversibleStatusEnum {

    POSTED(0, "已入账"),
    REVERSING(10, "冲账中"),
    REVERSED(20, "已冲账"),
    FAILED(30, "冲账失败");

    /**
     * 状态值
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

}
