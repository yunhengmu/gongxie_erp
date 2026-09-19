package cn.iocoder.yudao.module.ai.dal.dataobject.reversible;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 可撤回操作账本帧 DO
 *
 * append-only：帧一旦写入，归属、入参、工具名永不修改，只允许 status 迁移与 reversed* 回填。
 *
 * 继承 {@link TenantBaseDO}，因此 {@code tenant_id} 由多租户插件自动注入，无需手写过滤条件。
 *
 * @author hengmu
 */
@TableName("ai_reversible_ledger")
@KeySequence("ai_reversible_ledger_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversibleLedgerDO extends TenantBaseDO {

    /**
     * 账本帧编号
     */
    @TableId
    private Long id;

    /**
     * 对话编号
     *
     * 关联 {@link cn.iocoder.yudao.module.ai.dal.dataobject.chat.AiChatConversationDO#getId()}
     */
    private Long conversationId;

    /**
     * 撤回单元：用户消息编号
     *
     * 关联 {@link cn.iocoder.yudao.module.ai.dal.dataobject.chat.AiChatMessageDO#getId()}
     */
    private Long messageId;

    /**
     * 正函数 Bean 名
     */
    private String toolName;

    /**
     * 正函数入参快照（JSON）
     */
    private String toolInput;

    /**
     * 业务凭证号
     */
    private String bizVoucher;

    /**
     * 冲账函数 Bean 名
     */
    private String reverseBean;

    /**
     * 状态
     *
     * 枚举 {@link cn.iocoder.yudao.module.ai.framework.reversible.core.enums.ReversibleStatusEnum}
     */
    private Integer status;

    /**
     * 可撤回窗口（分钟）
     *
     * 入账时从 {@code @Reversible.windowMinutes()} 或全局配置固化下来，
     * 因为撤回时只能看到账本帧、拿不到注解
     */
    private Integer windowMinutes;

    /**
     * 冲账凭证号
     */
    private String reversedVoucher;

    /**
     * 撤回原因
     */
    private String reverseReason;

    /**
     * 冲账时间
     */
    private LocalDateTime reversedTime;

}
