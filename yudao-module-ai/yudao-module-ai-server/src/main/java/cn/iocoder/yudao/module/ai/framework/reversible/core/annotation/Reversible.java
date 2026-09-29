package cn.iocoder.yudao.module.ai.framework.reversible.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author hengmu
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Reversible {

    /**
     * 冲账目标 Bean 名
     *
     * 值会原样写进账本列，撤回时按它取 Bean，因此必须显式声明；
     * 留空会在入账时抛异常，切面不做「按正函数所在类反查」的回落。
     */
    String reverseBeanName() default "";

    /**
     * 冲账方法名（可空）
     *
     * 填写后在 {@link #reverseBeanName()} 指向的 Bean 内反射调用该方法，
     * 签名固定为 {@code String methodName(ReversibleContext context)}，
     * 此时该 Bean 无需实现 {@link cn.iocoder.yudao.module.ai.framework.reversible.core.ReversibleTool}；
     * 留空则要求该 Bean 实现 ReversibleTool 并调用其 {@code reverse()}。
     *
     * 典型用法：反函数与正函数写在同一个类，填
     * {@code @Reversible(reverseBeanName = "personService", reverseMethodName = "deletePerson")}。
     */
    String reverseMethodName() default "";

    int windowMinutes() default 0;

}
