package cn.iocoder.yudao.module.ai.framework.reversible.core;

import cn.hutool.core.util.StrUtil;
import org.springframework.util.Assert;

/**
 * 冲账目标引用
 *
 * 承载「Bean 名 + 可空方法名」两部分，两种形态：
 * <ul>
 *     <li>方法名为空：Bean 级，该 Bean 需实现 {@link ReversibleTool}，调用其 {@code reverse()}。</li>
 *     <li>方法名非空：方法级，反射调用该 Bean 内签名
 *     {@code String methodName(ReversibleContext context)} 的 public 方法。</li>
 * </ul>
 *
 * {@code beanName#methodName} 是 {@link #toExpression()} 与 {@link #parse(String)} 的
 * <b>账本列序列化格式</b>，只用于持久化往返；注解侧 {@code @Reversible.reverseBeanName()}
 * 只接受纯 Bean 名，方法名请用 {@code @Reversible.reverseMethodName()} 声明。
 *
 * @author hengmu
 */
public record ReverseFunctionRef(String beanName, String methodName) {

    private static final String SEPARATOR = "#";

    public ReverseFunctionRef {
        Assert.hasText(beanName, "beanName 不能为空");
    }

    public static ReverseFunctionRef parse(String expression) {
        String expr = StrUtil.trim(expression);
        Assert.hasText(expr, "reverseBeanName 不能为空");
        int idx = expr.indexOf(SEPARATOR);
        if (idx < 0) {
            return new ReverseFunctionRef(expr, null);
        }
        String beanName = expr.substring(0, idx).trim();
        String methodName = expr.substring(idx + SEPARATOR.length()).trim();
        Assert.hasText(beanName, () -> StrUtil.format("reverseBeanName({}) 格式错误，# 前缺少 Bean 名", expression));
        Assert.hasText(methodName, () -> StrUtil.format("reverseBeanName({}) 格式错误，# 后缺少方法名", expression));
        return new ReverseFunctionRef(beanName, methodName);
    }

    /**
     * @return 是否为方法级绑定（{@code beanName#methodName}）
     */
    public boolean isMethodLevel() {
        return StrUtil.isNotBlank(methodName);
    }

    /**
     * 序列化为账本存储串，供 {@link #parse(String)} 还原
     */
    public String toExpression() {
        return isMethodLevel() ? beanName + SEPARATOR + methodName : beanName;
    }

}
