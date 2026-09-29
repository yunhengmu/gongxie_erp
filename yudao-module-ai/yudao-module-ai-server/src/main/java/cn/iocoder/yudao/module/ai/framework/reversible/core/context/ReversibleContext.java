package cn.iocoder.yudao.module.ai.framework.reversible.core.context;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * @author hengmu
 */
@Data
@Accessors(chain = true)
public class ReversibleContext {

    private Long ledgerId;

    private Long messageId;

    private String toolName;

    private String bizVoucher;

    private String rawInput;

    private Long tenantId;

    private Long operatorId;

    private String reason;

    private LocalDateTime reversedAt;

    public <T> T input(Class<T> type) {
        if (rawInput == null) {
            return null;
        }
        return JsonUtils.parseObject(rawInput, type);
    }

}
