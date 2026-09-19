package cn.iocoder.yudao.module.ai.dal.mysql.reversible;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ai.dal.dataobject.reversible.ReversibleLedgerDO;
import cn.iocoder.yudao.module.ai.framework.reversible.core.enums.ReversibleStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 可撤回操作账本 Mapper
 *
 * 所有查询都由多租户插件自动追加 {@code tenant_id} 条件，无需手写。
 *
 * @author yudao
 */
@Mapper
public interface ReversibleLedgerMapper extends BaseMapperX<ReversibleLedgerDO> {

    /**
     * 查询某条用户消息下的全部账本帧，按执行顺序倒序（用于逆序冲账）
     *
     * @param messageId 用户消息编号
     * @return 账本帧列表
     */
    default List<ReversibleLedgerDO> selectListByMessageId(Long messageId) {
        return selectList(new LambdaQueryWrapperX<ReversibleLedgerDO>()
                .eq(ReversibleLedgerDO::getMessageId, messageId)
                .orderByDesc(ReversibleLedgerDO::getId));
    }

    /**
     * 统计该会话中「比指定消息更晚」的未冲账帧数量
     *
     * 对应撤回的因果闸门：存在更晚的未撤回操作时拒绝本轮撤回，
     * 避免「消息 A 建单、消息 B 基于 A 拣货，单撤 A 后 B 的帧成为孤儿分录」。
     *
     * @param conversationId 对话编号
     * @param messageId      用户消息编号
     * @return 数量
     */
    default Long selectPostedCountAfterMessage(Long conversationId, Long messageId) {
        return selectCount(new LambdaQueryWrapperX<ReversibleLedgerDO>()
                .eq(ReversibleLedgerDO::getConversationId, conversationId)
                .gt(ReversibleLedgerDO::getMessageId, messageId)
                .eq(ReversibleLedgerDO::getStatus, ReversibleStatusEnum.POSTED.getStatus()));
    }

    /**
     * 状态 CAS 迁移
     *
     * 只有「当前状态确实等于 fromStatus」时才会更新成功（返回 1）。
     * 撤回时用它做占位防并发：多实例、多请求同时撤回同一条消息，只有一个能抢到。
     *
     * @param id         账本帧编号
     * @param fromStatus 期望的原状态
     * @param toStatus   目标状态
     * @return 影响行数，1 表示抢到
     */
    default int casStatus(Long id, Integer fromStatus, Integer toStatus) {
        return update(new ReversibleLedgerDO().setStatus(toStatus),
                new LambdaUpdateWrapper<ReversibleLedgerDO>()
                        .eq(ReversibleLedgerDO::getId, id)
                        .eq(ReversibleLedgerDO::getStatus, fromStatus));
    }

}
