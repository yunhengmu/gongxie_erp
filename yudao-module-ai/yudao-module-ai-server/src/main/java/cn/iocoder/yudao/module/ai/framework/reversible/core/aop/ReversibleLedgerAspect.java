package cn.iocoder.yudao.module.ai.framework.reversible.core.aop;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.dal.dataobject.reversible.ReversibleLedgerDO;
import cn.iocoder.yudao.module.ai.dal.mysql.reversible.ReversibleLedgerMapper;
import cn.iocoder.yudao.module.ai.framework.reversible.core.ReversibleTool;
import cn.iocoder.yudao.module.ai.framework.reversible.core.VoucherAware;
import cn.iocoder.yudao.module.ai.framework.reversible.core.annotation.Reversible;
import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContextHolder;
import cn.iocoder.yudao.module.ai.framework.reversible.core.enums.ReversibleStatusEnum;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 入账切面：拦截所有 {@link Reversible} 声明的正函数，在业务执行成功后写入一笔账本帧。
 *
 * <p><b>注解可以标在两层，切面都支持</b>：
 * <ol>
 *     <li><b>业务方法</b>（推荐）—— 业务层是「业务操作」的最小单元，且同一操作可能经
 *         REST / 定时任务 / 其他 Service 多个入口进入，入账语义不该取决于调用入口。
 *         此时上下文由 {@code ReversibleBridgeAspect} 写入 {@link ReversibleContextHolder}。</li>
 *     <li>AI 工具方法 —— 工具层持有 {@code ToolContext}，切面直接从方法参数取。
 *         适合「只该由 AI 触发、没有业务层」的场景。</li>
 * </ol>
 *
 * 非 AI 入口（REST 接口等）调用带 {@link Reversible} 的业务方法时，上下文为空，
 * 切面<b>跳过入账</b>并直接执行业务 —— 这是正常路径，不是错误。
 *
 * 事务边界：与业务方法在<b>同一事务</b>。业务失败则账不入。
 * 这里没有用 {@code @Transactional} 注解，而是显式走 {@link PlatformTransactionManager}——
 * 避免与业务方法自身的 {@code @Transactional} 抢 order（默认 LOWEST_PRECEDENCE），
 * 也避免用 RuntimeException 包装 {@code proceed()} 抛出的原异常。
 *
 * @author hengmu
 */
@Aspect
@Component
@Slf4j
public class ReversibleLedgerAspect {

    @Resource
    private ReversibleLedgerMapper ledgerMapper;
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 全局默认可撤回窗口（分钟）
     */
    @Value("${yudao.ai.undo.window-minutes:30}")
    private Integer defaultWindowMinutes;

    private final PlatformTransactionManager transactionManager;

    /**
     * Bean 名缓存：key 为工具实现类，value 为 Bean 名
     *
     * 避免每次都走 getBeanNamesForType 遍历容器。
     */
    private final Map<Class<?>, String> beanNameCache = new ConcurrentHashMap<>();

    public ReversibleLedgerAspect(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Around("@annotation(reversible)")
    public Object around(ProceedingJoinPoint joinPoint, Reversible reversible) throws Throwable {
        // 1. 取撤回单元与归属上下文
        //    两级来源，先查开销小的：① ReversibleContextHolder —— 标在业务方法上（主路径）
        //                            ② 方法参数里的 ToolContext —— 标在工具方法上
        //    详见 resolveContext 的注释
        Context context = resolveContext(joinPoint);
        if (context == null) {
            // 非 AI 触发的调用（REST 接口、定时任务、其他 Service）——无需入账，直接执行业务。
            // 这里不额外开启事务，业务方法自身的 @Transactional 照常工作。
            log.debug("[around][方法({}) 无 AI 上下文，跳过入账]", joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        // 2. 解析正函数名（审计用）与冲账函数 Bean 名（调用用）
        //    注意二者可能不同：@Component("xxx") 形态下工具名 = Bean 名；
        //    而 @Tool(name = "yyy") 形态下工具名是注解里的值，Bean 名另有其人
        String toolName = resolveToolName(joinPoint);
        String reverseBean = StrUtil.blankToDefault(reversible.reverseBean(), resolveBeanName(joinPoint.getTarget()));
        // fail-fast 校验冲账 Bean 可用。必须在入账时就查，不能拖到用户点撤回 —— 见方法注释
        validateReverseBean(reverseBean, joinPoint);

        // 3. 捕获入参快照（排除 ToolContext 本身）
        Object[] bizArgs = Stream.of(joinPoint.getArgs())
                .filter(arg -> !(arg instanceof ToolContext))
                .toArray();
        String toolInput = bizArgs.length == 1
                ? JsonUtils.toJsonString(bizArgs[0])   // 单参：直接存该对象
                : JsonUtils.toJsonString(bizArgs);     // 多参：存数组

        // 4. 窗口：注解优先，否则用全局配置
        Integer windowMinutes = reversible.windowMinutes() > 0
                ? reversible.windowMinutes() : defaultWindowMinutes;

        // 5. 执行业务 + 写账本帧，二者同一事务
        //    注意：必须显式设置租户上下文。流式场景下工具执行在 Flux 线程，
        //    而 TenantContextHolder 是 TransmittableThreadLocal，不会自动继承调用线程的值。
        Long originTenantId = TenantContextHolder.getTenantId();
        if (context.tenantId() != null) {
            TenantContextHolder.setTenantId(context.tenantId());
        }
        try {
            TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable e) {
                rollbackQuietly(transaction);
                throw e;
            }
            try {
                ReversibleLedgerDO ledger = new ReversibleLedgerDO()
                        .setConversationId(context.conversationId())
                        .setMessageId(context.messageId())
                        .setToolName(toolName)
                        .setToolInput(toolInput)
                        .setReverseBean(reverseBean)
                        .setStatus(ReversibleStatusEnum.POSTED.getStatus())
                        .setWindowMinutes(windowMinutes);
                if (result instanceof VoucherAware aware) {
                    ledger.setBizVoucher(aware.getBizVoucher());
                }
                ledgerMapper.insert(ledger);
                transactionManager.commit(transaction);
                return result;
            } catch (Throwable e) {
                rollbackQuietly(transaction);
                throw e;
            }
        } finally {
            TenantContextHolder.setTenantId(originTenantId);
        }
    }

    /**
     * 从方法参数中找出 Spring AI 注入的 {@link ToolContext}
     */
    private ToolContext findToolContext(Object[] args) {
        return Stream.of(args)
                .filter(ToolContext.class::isInstance)
                .map(ToolContext.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析本次调用的 AI 上下文
     *
     * <p>两级来源，任一取到即可。<b>先查 Holder、再查方法参数</b>，理由有两点：
     * <ol>
     *     <li>开销：Holder 是一次 {@code ThreadLocal.get()}，而找 ToolContext 要遍历参数数组
     *         并创建 Stream / Optional 对象，更贵。便宜的放前面</li>
     *     <li>主次：{@code @Reversible} 的推荐写法是标在<b>业务方法</b>上，那条路径直接从
     *         Holder 取值，根本走不到第二级 —— 让主路径排在最前面</li>
     * </ol>
     *
     * <p>两个来源的值在「标工具方法」场景下是<b>相同</b>的：桥接切面会把 ToolContext 里的内容
     * 原样写进 Holder。所以谁先谁后不影响取值结果，只是顺序上的讲究。
     *
     * @return 上下文；返回 {@code null} 表示「非 AI 触发的调用」，调用方应跳过入账
     */
    private Context resolveContext(ProceedingJoinPoint joinPoint) {
        // 来源一：ReversibleContextHolder（主路径）
        //        由 ReversibleBridgeAspect 在工具方法入口写入，@Reversible 标在业务方法时走这里
        ReversibleContextHolder.Snapshot snapshot = ReversibleContextHolder.get();
        if (snapshot != null) {
            if (snapshot.messageId() == null) {
                throw new IllegalStateException(StrUtil.format(
                        "[resolveContext][方法({}) 的 AI 上下文中缺少 {}，请检查 ReversibleBridgeAspect 的桥接]",
                        joinPoint.getSignature().toShortString(), AiUtils.TOOL_CONTEXT_MESSAGE_ID));
            }
            return new Context(snapshot.messageId(), snapshot.conversationId(), snapshot.tenantId());
        }
        // 来源二：方法参数的 ToolContext（次要路径）
        //        @Reversible 标在工具方法时走这里；只有来源一取不到才值得花这个遍历开销
        ToolContext toolContext = findToolContext(joinPoint.getArgs());
        if (toolContext != null) {
            Long messageId = (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_MESSAGE_ID);
            if (messageId == null) {
                // fail-fast：静默跳过会导致「以为可撤、实际没账本」，是最危险的失败模式
                throw new IllegalStateException(StrUtil.format(
                        "[resolveContext][方法({}) 的 ToolContext 中缺少 {}，请检查 buildCommonToolContext 的调用]",
                        joinPoint.getSignature().toShortString(), AiUtils.TOOL_CONTEXT_MESSAGE_ID));
            }
            return new Context(messageId,
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_CONVERSATION_ID),
                    (Long) toolContext.getContext().get(AiUtils.TOOL_CONTEXT_TENANT_ID));
        }
        // 两个来源都为空，再区分「异常配置」与「非 AI 入口的正常调用」
        if (declaresToolContext(joinPoint)) {
            // 声明了 ToolContext 参数却取不到值 —— Spring AI 不会这样传，属于异常配置
            throw new IllegalStateException(StrUtil.format(
                    "[resolveContext][方法({}) 声明了 ToolContext 参数，但取不到参数值]",
                    joinPoint.getSignature().toShortString()));
        }
        return null; // 非 AI 入口调用：REST 接口、定时任务、其他 Service
    }

    /**
     * 判断方法签名里是否声明了 {@link ToolContext} 参数
     *
     * 这里用精确类型比较，而不用 {@code isAssignableFrom}：后者会把 {@code Object} 类型的参数
     * 也判为 true —— Spring AI 2.0.0 正是踩了这个坑（见 PR #6744），2.0.1 才修好。
     */
    private boolean declaresToolContext(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return false;
        }
        return Stream.of(signature.getMethod().getParameterTypes())
                .anyMatch(ToolContext.class::equals);
    }

    /**
     * 解析工具名（LLM 看到的名字，即 {@code ai_tool.name}）
     *
     * 本项目里工具有三种声明形态，工具名的来源不同：
     * <ol>
     *     <li>{@code @Component("directory_list")} + {@code Function}：工具名 = Bean 名</li>
     *     <li>{@code @Component("user_profile_query")} + {@code BiFunction}：工具名 = Bean 名</li>
     *     <li>{@code @Tool(name = "...")} 注解方法（如 DbQueryToolFunction）：工具名 = 注解的 name</li>
     * </ol>
     */
    private String resolveToolName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Tool tool = AnnotatedElementUtils.findMergedAnnotation(signature.getMethod(), Tool.class);
        if (tool != null && StrUtil.isNotBlank(tool.name())) {
            return tool.name();
        }
        return resolveBeanName(joinPoint.getTarget());
    }

    /**
     * 按目标类型反查 Bean 名
     *
     * 工具 Bean 名即 {@code ai_tool.name}，因此必须是容器里的 Bean 名
     * （例如 {@code @Component("user_profile_query")} 的 user_profile_query）。
     */
    private String resolveBeanName(Object target) {
        Class<?> targetClass = AopUtils.getTargetClass(target);
        return beanNameCache.computeIfAbsent(targetClass, clazz -> {
            String[] names = applicationContext.getBeanNamesForType(clazz);
            if (names.length == 0) {
                throw new IllegalStateException(StrUtil.format(
                        "[resolveBeanName][容器中找不到类型({}) 的 Bean]", clazz.getName()));
            }
            if (names.length > 1) {
                throw new IllegalStateException(StrUtil.format(
                        "[resolveBeanName][类型({}) 对应多个 Bean({})，请显式指定 @Reversible.reverseBean]",
                        clazz.getName(), Arrays.toString(names)));
            }
            return names[0];
        });
    }

    /**
     * 校验冲账 Bean 存在且实现了 {@link ReversibleTool}
     *
     * <p><b>为什么必须在入账时校验</b>：{@link #resolveBeanName} 只按类型反查 Bean 名，
     * <b>不校验那个类是否实现了 ReversibleTool</b>。于是「{@code @Reversible} 留空 reverseBean，
     * 但正函数所在类没实现 ReversibleTool」这种配置错误：
     * <ul>
     *     <li>入账时毫无异常，账本帧正常写入 —— 看起来一切正常</li>
     *     <li>用户点撤回时才在 {@code getBean(name, ReversibleTool.class)} 抛
     *         {@code BeanNotOfRequiredTypeException}</li>
     *     <li>而那时该帧已被 CAS 改成 {@code REVERSING}，随后标 {@code FAILED} —— <b>永久不可再撤</b></li>
     * </ul>
     *
     * <p>这个坑在「{@code @Reversible} 标在业务方法上」的场景下尤其容易踩：
     * 业务 Service 通常不实现 ReversibleTool（薄壳是独立的 Adapter 类），
     * 所以标业务方法时<b>必须显式写 reverseBean</b> 指过去。
     */
    private void validateReverseBean(String reverseBean, ProceedingJoinPoint joinPoint) {
        if (!applicationContext.containsBean(reverseBean)) {
            throw new IllegalStateException(StrUtil.format(
                    "[validateReverseBean][方法({}) 的冲账 Bean({}) 不存在于容器中]",
                    joinPoint.getSignature().toShortString(), reverseBean));
        }
        if (!applicationContext.isTypeMatch(reverseBean, ReversibleTool.class)) {
            throw new IllegalStateException(StrUtil.format(
                    "[validateReverseBean][方法({}) 的冲账 Bean({}) 未实现 ReversibleTool]。"
                            + "若 @Reversible 标在业务方法上，请显式写 reverseBean 指向冲账薄壳；"
                            + "留空则按正函数所在类反查，要求该类自身实现 ReversibleTool",
                    joinPoint.getSignature().toShortString(), reverseBean));
        }
    }

    private void rollbackQuietly(TransactionStatus status) {
        try {
            transactionManager.rollback(status);
        } catch (Exception ex) {
            log.warn("[rollbackQuietly][事务回滚失败]", ex);
        }
    }

    /**
     * 一次调用的 AI 上下文
     *
     * @param messageId      撤回单元：用户消息编号
     * @param conversationId 对话编号
     * @param tenantId       租户编号
     */
    private record Context(Long messageId, Long conversationId, Long tenantId) {
    }

}
