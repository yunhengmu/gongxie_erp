package cn.iocoder.yudao.module.ai.framework.reversible.core.aop;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.dal.dataobject.reversible.ReversibleLedgerDO;
import cn.iocoder.yudao.module.ai.dal.mysql.reversible.ReversibleLedgerMapper;
import cn.iocoder.yudao.module.ai.framework.reversible.core.ReverseFunctionRef;
import cn.iocoder.yudao.module.ai.framework.reversible.core.ReversibleTool;
import cn.iocoder.yudao.module.ai.framework.reversible.core.VoucherAware;
import cn.iocoder.yudao.module.ai.framework.reversible.core.annotation.Reversible;
import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContext;
import cn.iocoder.yudao.module.ai.framework.reversible.core.context.ReversibleContextHolder;
import cn.iocoder.yudao.module.ai.framework.reversible.core.enums.ReversibleStatusEnum;
import cn.iocoder.yudao.module.ai.util.AiUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.stream.Stream;

/**
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

    @Value("${yudao.ai.undo.window-minutes:30}")
    private Integer defaultWindowMinutes;

    private final PlatformTransactionManager transactionManager;

    public ReversibleLedgerAspect(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Around("@annotation(reversible)")
    public Object around(ProceedingJoinPoint joinPoint, Reversible reversible) throws Throwable {
        Context context = resolveContext(joinPoint);
        if (context == null) {
            log.debug("[around][方法({}) 无上下文，跳过入账]", joinPoint.getSignature().toShortString());
            return joinPoint.proceed();
        }

        String toolName = resolveToolName(joinPoint);
        ReverseFunctionRef reverseFunctionRef = resolveReverseFunction(reversible);
        validateReverseFunction(reverseFunctionRef, joinPoint);

        Object[] bizArgs = Stream.of(joinPoint.getArgs())
                .filter(arg -> !(arg instanceof ToolContext))
                .toArray();

        String toolInput = bizArgs.length == 1
                ? JsonUtils.toJsonString(bizArgs[0])
                : JsonUtils.toJsonString(bizArgs);

        Integer windowMinutes = reversible.windowMinutes() > 0
                ? reversible.windowMinutes() : defaultWindowMinutes;

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
                        .setReverseBeanName(reverseFunctionRef.toExpression())
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

    private Context resolveContext(ProceedingJoinPoint joinPoint) {
        ReversibleContextHolder.Snapshot snapshot = ReversibleContextHolder.get();
        if (snapshot != null) {
            if (snapshot.messageId() == null) {
                throw new IllegalStateException(StrUtil.format(
                        "[resolveContext][方法({}) 的  上下文中缺少 {}，请检查 ReversibleBridgeAspect 的桥接]",
                        joinPoint.getSignature().toShortString(), AiUtils.TOOL_CONTEXT_MESSAGE_ID));
            }
            return new Context(snapshot.messageId(), snapshot.conversationId(), snapshot.tenantId());
        }
        if (declaresToolContext(joinPoint)) {
            throw new IllegalStateException(StrUtil.format(
                    "[resolveContext][方法({}) 声明了 ToolContext 参数，但取不到参数值]",
                    joinPoint.getSignature().toShortString()));
        }
        return null;
    }

    private boolean declaresToolContext(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return false;
        }
        return Stream.of(signature.getMethod().getParameterTypes())
                .anyMatch(ToolContext.class::equals);
    }

    private String resolveToolName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 工具函数上显式定义的工具名优先
        Tool tool = AnnotatedElementUtils.findMergedAnnotation(signature.getMethod(), Tool.class);
        if (tool != null && StrUtil.isNotBlank(tool.name())) {
            return tool.name();
        }
        // 未显式指定时，Spring AI 自动补全的工具名即方法名
        return signature.getMethod().getName();
    }

    private ReverseFunctionRef resolveReverseFunction(Reversible reversible) {
        String beanName = StrUtil.trimToNull(reversible.reverseBeanName());
        if (beanName == null) {
            throw new IllegalStateException("[resolveReverseFunction][未指定 @Reversible.reverseBeanName，"
                    + "账本需要记录明确的冲账目标 Bean]");
        }
        return new ReverseFunctionRef(beanName, StrUtil.trimToNull(reversible.reverseMethodName()));
    }

    private void validateReverseFunction(ReverseFunctionRef ref, ProceedingJoinPoint joinPoint) {
        if (!applicationContext.containsBean(ref.beanName())) {
            //确定有bean在容器中存在
            throw new IllegalStateException(StrUtil.format(
                    "[validateReverseFunction][方法({}) 的冲账 Bean({}) 不存在于容器中]",
                    joinPoint.getSignature().toShortString(), ref.beanName()));
        }
        if (ref.isMethodLevel()) {
            // 确定bean上面有对应方法
            Class<?> beanClass = AopUtils.getTargetClass(applicationContext.getBean(ref.beanName()));
            try {
                beanClass.getMethod(ref.methodName(), ReversibleContext.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(StrUtil.format(
                        "[validateReverseFunction][方法({}) 的冲账方法({}#{}(ReversibleContext)) 不存在，"
                                + "请保证其为该 Bean 上的 public 方法且签名为 (ReversibleContext)->String",
                        joinPoint.getSignature().toShortString(), ref.beanName(), ref.methodName()), e);
            }
            return;
        }
        if (!applicationContext.isTypeMatch(ref.beanName(), ReversibleTool.class)) {
            throw new IllegalStateException(StrUtil.format(
                    "[validateReverseFunction][方法({}) 的冲账 Bean({}) 未实现 ReversibleTool]。"
                            + "未指定 reverseMethodName 时，Bean 级冲账要求实现 ReversibleTool；"
                            + "如需调用 Bean 内的任意方法，请改用 reverseMethodName 指定方法名",
                    joinPoint.getSignature().toShortString(), ref.beanName()));
        }
    }

    private void rollbackQuietly(TransactionStatus status) {
        try {
            transactionManager.rollback(status);
        } catch (Exception ex) {
            log.warn("[rollbackQuietly][事务回滚失败]", ex);
        }
    }

    private record Context(Long messageId, Long conversationId, Long tenantId) {
    }

}
