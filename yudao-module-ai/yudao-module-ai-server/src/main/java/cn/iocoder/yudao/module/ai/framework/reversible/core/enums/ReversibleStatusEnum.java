package cn.iocoder.yudao.module.ai.framework.reversible.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hengmu
 */
@Getter
@AllArgsConstructor
public enum ReversibleStatusEnum {

    POSTED(0, "已入账"),
    REVERSING(10, "冲账中"),
    REVERSED(20, "已冲账"),
    FAILED(30, "冲账失败");

    private final Integer status;

    private final String name;

}
