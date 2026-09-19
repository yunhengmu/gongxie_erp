package cn.iocoder.yudao.module.ai.framework.reversible.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个「可撤回操作」的正函数。
 *
 * 撤回 = 冲账，不删除原记录，只追加反向分录。
 *
 * <p><b>可以标在两层，推荐标在业务方法上</b>：
 * <ol>
 *     <li><b>业务方法（推荐）</b> —— 业务层是「业务操作」的最小单元；同一操作可能经
 *         REST / 定时任务 / 其他 Service 多个入口进入，入账语义不该取决于调用入口。
 *         此时 messageId 由 {@code ReversibleBridgeAspect} 写入 {@code ReversibleContextHolder}，
 *         业务方法签名里<b>不应</b>出现 ToolContext。
 *         注意：这种写法<b>必须显式写 reverseBean</b> 指向冲账薄壳
 *         —— 业务 Service 通常自身不实现 ReversibleTool。</li>
 *     <li><b>AI 工具方法</b> —— 工具层持有 ToolContext，切面直接从方法参数取。
 *         适合「只该由 AI 触发、没有业务层」的场景。</li>
 * </ol>
 *
 * <p><b>使用约束（四条）</b>：
 * <ol>
 *     <li>必须是 {@code public} 方法，且注解标在<b>实现类</b>方法上，不能标在接口方法上。
 *         原因：Spring AOP 只能拦截 public 方法，且 {@code @annotation} 切点匹配不到接口上的注解
 *         —— 两种情况都会<b>静默失效</b>，不报错。</li>
 *     <li>方法<b>不得吞异常</b>。不能用 try-catch 包住业务后返回"操作失败"字符串，
 *         否则业务失败会被当成成功入账。切面判定成功的依据是 {@code proceed()} 没抛异常。</li>
 *     <li>标在业务方法上时，业务参数<b>应收敛为单个 DTO</b>。
 *         原因：多参时入参快照存的是数组 JSON，冲账函数无法用
 *         {@code ReversibleContext#input(Class)} 反序列化。详见该方法注释。</li>
 *     <li>标在工具方法上时，签名必须能拿到 ToolContext
 *         （即 {@code BiFunction<Request, ToolContext, Response>}，或方法参数中声明 ToolContext）。
 *         原因：撤回单元（messageId）靠 ToolContext 传递，见 {@code AiUtils.TOOL_CONTEXT_MESSAGE_ID}。</li>
 * </ol>
 *
 * <p>冲账函数需实现 {@link cn.iocoder.yudao.module.ai.framework.reversible.core.ReversibleTool}。
 *
 * @author hm
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Reversible {

    /**
     * 冲账函数所在 Bean 名
     *
     * 留空表示冲账函数与正函数在同一个 Bean，此时按类型反查容器（要求容器内该类型唯一）
     */
    String reverseBean() default "";

    /**
     * 可撤回窗口（分钟）
     *
     * 0 表示使用全局配置 {@code yudao.ai.undo.window-minutes}
     */
    int windowMinutes() default 0;

}
