package org.dromara.ai.service.impl;

import org.dromara.ai.test.AiTestSupport;

/**
 * AI Service 单元测试的静态依赖上下文（薄封装，实现在 {@link AiTestSupport}）。
 *
 * <p>保留本类是为了兼容既有测试类；新写的测试请直接使用 {@link AiTestSupport}。</p>
 */
final class AiServiceTestContext {

    private AiServiceTestContext() {
    }

    /**
     * 触发最小容器与 TableInfo 初始化（幂等）。
     */
    static void initialize() {
        AiTestSupport.initialize();
    }

    /**
     * 按属性名浅拷贝构造目标对象，模拟 Mapstruct Plus 的转换语义。
     *
     * @param source     源对象
     * @param targetType 目标类型
     * @param <T>        目标泛型
     * @return 目标实例
     */
    static <T> T copy(Object source, Class<T> targetType) {
        return AiTestSupport.copy(source, targetType);
    }

}