package cn.iocoder.yudao.module.ai.framework.reversible.core;

import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContext;

/**
 * 冲账函数统一接口。
 *
 * 每个 {@link cn.iocoder.yudao.module.ai.framework.reversible.core.annotation.Reversible} 声明的正函数，
 * 都要有一个实现本接口的冲账函数与之配对。
 *
 * <p>为什么必须是接口：撤回时通过
 * {@code applicationContext.getBean(reverseBean, ReversibleTool.class)} 取 Bean，
 * 容器返回的必然是代理对象，因此事务与切面天然生效；
 * 且 JDK 动态代理与 CGLIB 代理两种模式下都能取到方法。
 * 若改用「拿 Bean 后反射方法名」，在 JDK 代理下会 {@code NoSuchMethodException}。
 *
 * @author yudao
 */
public interface ReversibleTool {

    /**
     * 执行冲账，向业务账本追加一笔反向分录
     *
     * 约定：
     * <ul>
     *     <li>只追加反向分录，绝不 UPDATE 业务余额</li>
     *     <li>反向分录必须关联 {@link ReversibleContext#getBizVoucher()} 原凭证号</li>
     *     <li>不基于当前实时状态逆向计算，不覆盖原操作结果</li>
     * </ul>
     *
     * @param context 冲账上下文
     * @return 冲账凭证号
     */
    String reverse(ReversibleContext context);

}
