package cn.iocoder.yudao.module.ai.framework.reversible.core;

/**
 * 业务凭证号感知接口。
 *
 * 可撤回工具的返回值（{@code Response}）实现本接口后，
 * 入账切面会自动把凭证号写入账本帧的 {@code biz_voucher} 字段，
 * 冲账函数即可通过 {@code ReversibleContext#getBizVoucher()} 定位要冲的那笔分录。
 *
 * <p>不实现也可以：此时 {@code biz_voucher} 为 null，冲账函数需要自己从入参快照中推导。
 * 但强烈建议实现——「反向分录必须关联原凭证号」是账本可审计的基础。
 *
 * @author yudao
 */
public interface VoucherAware {

    /**
     * 获得业务凭证号
     *
     * @return 凭证号
     */
    String getBizVoucher();

}
