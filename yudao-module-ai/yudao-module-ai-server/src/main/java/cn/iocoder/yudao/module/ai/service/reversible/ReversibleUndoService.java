package cn.iocoder.yudao.module.ai.service.reversible;

/**
 * 可撤回操作 Service 接口
 *
 * 撤回 = 冲账：不删除原记录，只追加反向分录。
 *
 * @author yudao
 */
public interface ReversibleUndoService {

    /**
     * 撤回一条用户消息触发的全部 Agent 操作
     *
     * @param messageId 消息编号。传 user 消息或它的 assistant 回复消息都可以，内部会归一化
     * @param userId    发起人编号（用于归属校验，防止撤回他人消息）
     * @param reason    撤回原因（可为空）
     */
    void undo(Long messageId, Long userId, String reason);

}
