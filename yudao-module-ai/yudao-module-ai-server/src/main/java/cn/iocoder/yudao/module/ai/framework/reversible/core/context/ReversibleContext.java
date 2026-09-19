package cn.iocoder.yudao.module.ai.framework.reversible.core.context;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 冲账上下文：冲账函数能拿到的全部原操作信息。
 *
 * 对应设计文档「冲账函数入参使用统一的冲账上下文对象，不依赖反射时的参数推断」。
 *
 * @author hengmu
 */
@Data
@Accessors(chain = true)
public class ReversibleContext {

    /**
     * 账本帧编号，关联 {@code ai_reversible_ledger.id}
     */
    private Long ledgerId;

    /**
     * 撤回单元：用户消息编号，关联 {@code ai_chat_message.id}
     */
    private Long messageId;

    /**
     * 正函数 Bean 名
     */
    private String toolName;

    /**
     * 业务凭证号
     *
     * 冲账函数定位「要冲哪一笔分录」的唯一必需字段
     */
    private String bizVoucher;

    /**
     * 正函数入参 JSON 快照
     */
    private String rawInput;

    /**
     * 租户编号
     */
    private Long tenantId;

    /**
     * 撤回发起人
     */
    private Long operatorId;

    /**
     * 撤回原因
     */
    private String reason;

    /**
     * 冲账时间
     */
    private LocalDateTime reversedAt;

    /**
     * 把入参快照反序列化为冲账函数需要的类型
     *
     * 类型由冲账函数自己指定，框架不猜——这是 {@code rawInput} 以 JSON 存储的必然要求。
     *
     * <p><b>⚠️ 只适用于「正函数只有一个业务参数」的情况。</b>
     * 入账切面捕获入参快照时按<b>参数个数</b>分两种形态：
     * <ul>
     *     <li><b>单参</b> —— {@code rawInput} 是对象 JSON，本方法可用</li>
     *     <li><b>多参</b> —— {@code rawInput} 是数组 JSON（如 {@code ["RCV-001",1001,5]}），
     *         拿 POJO 类型调本方法会抛 Jackson {@code MismatchedInputException}，
     *         导致冲账失败、账本帧被标 {@code FAILED} 永久不可再撤</li>
     * </ul>
     *
     * <p>因此可撤回的正函数<b>应把业务参数收敛成一个 DTO</b>：
     * <pre>
     *     // ✅ 推荐：单参 DTO，input() 直接可用
     *     {@code @Reversible(reverseBean = "xxxAdapter")}
     *     public Long createReceive(WmsReceiveCreateReqDTO req) { ... }
     *
     *     // ❌ 多参：input() 用不了，冲账函数只能退而依赖 bizVoucher
     *     public Long createReceive(String receiveNo, Long skuId, Integer qty) { ... }
     * </pre>
     *
     * @param type 目标类型
     * @return 反序列化后的入参对象；快照为空时返回 null
     */
    public <T> T input(Class<T> type) {
        if (rawInput == null) {
            return null;
        }
        return JsonUtils.parseObject(rawInput, type);
    }

}
