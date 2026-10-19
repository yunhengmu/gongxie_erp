package org.dromara.ai.service;

import org.dromara.ai.domain.AiChannel;
import org.dromara.ai.domain.AiChannelThread;
import org.dromara.ai.domain.bo.AiChannelBo;
import org.dromara.ai.domain.bo.AiChannelIdentityBo;
import org.dromara.ai.domain.vo.AiChannelIdentityVo;
import org.dromara.ai.domain.vo.AiChannelThreadVo;
import org.dromara.ai.domain.vo.AiChannelVo;
import org.dromara.ai.domain.vo.ChannelUserLookupVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
import java.util.List;

/**
 * AI 渠道接入 Service 接口（REQ-AI-24）
 *
 * @author BizBuddy
 */
public interface IAiChannelService {

    // -----------------------------------------------------------------
    //  渠道配置
    // -----------------------------------------------------------------

    /**
     * 分页查询渠道列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiChannelVo> queryPageList(AiChannelBo bo, PageQuery pageQuery);

    /**
     * 查询渠道详情（凭据不回显，仅返回是否已配置）。
     *
     * @param id 主键
     * @return 渠道视图对象
     */
    AiChannelVo queryById(Long id);

    /**
     * 新增渠道（凭据加密落库）。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertByBo(AiChannelBo bo);

    /**
     * 修改渠道（凭据留空表示保持原值）。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateByBo(AiChannelBo bo);

    /**
     * 按主键集合删除渠道（逻辑删除；同时清理其身份映射与线程映射）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 加载启用中的渠道**运行期配置**（凭据已解密为明文）。
     *
     * <p>仅用于回调链路装配，返回值不得回显到任何接口；渠道不存在或已停用时返回 {@code null}。</p>
     *
     * @param channelCode 渠道编码
     * @return 已解密的渠道配置，或 null
     */
    AiChannel loadRuntimeConfig(String channelCode);

    /**
     * 查询启用中的**长连接**渠道编码清单（供应用启动时按需拉起连接）。
     *
     * <p>钉钉恒为长连接；飞书按 {@code mode=ws} 判定；其余平台为 Webhook（由外部平台回调驱动），不在此列。</p>
     *
     * @return 渠道编码清单
     */
    List<String> listEnabledLongConnectionChannelCodes();

    /**
     * 按手机号查询外部平台的用户标识（钉钉 userid / 飞书 open_id）。
     *
     * <p>仅**钉钉与飞书**支持（用渠道已存的应用凭据调平台通讯录接口）；其余平台抛业务异常。
     * 平台侧未查到该手机号时以返回值的 {@code found=false} 表达，不作为异常。</p>
     *
     * @param channelId 渠道主键
     * @param mobile    11 位手机号
     * @return 查询结果
     */
    ChannelUserLookupVo lookupExternalUserId(Long channelId, String mobile);

    // -----------------------------------------------------------------
    //  外部身份映射
    // -----------------------------------------------------------------

    /**
     * 分页查询外部身份映射。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiChannelIdentityVo> queryIdentityPageList(AiChannelIdentityBo bo, PageQuery pageQuery);

    /**
     * 新增外部身份映射（同渠道下外部用户标识唯一）。
     *
     * @param bo 业务对象
     * @return 是否新增成功
     */
    Boolean insertIdentity(AiChannelIdentityBo bo);

    /**
     * 修改外部身份映射。
     *
     * @param bo 业务对象
     * @return 是否修改成功
     */
    Boolean updateIdentity(AiChannelIdentityBo bo);

    /**
     * 删除外部身份映射（逻辑删除）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteIdentities(Collection<Long> ids);

    /**
     * 解析外部身份对应的平台用户（启用中的映射）。
     *
     * @param channelId       渠道主键
     * @param externalUserId  外部用户标识
     * @return 平台用户主键；未映射或映射已停用时返回 {@code null}
     */
    Long resolvePlatformUserId(Long channelId, String externalUserId);

    // -----------------------------------------------------------------
    //  消息线程映射
    // -----------------------------------------------------------------

    /**
     * 分页查询消息线程与会话映射。
     *
     * @param channelId 渠道主键（为空查全部）
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageResult<AiChannelThreadVo> queryThreadPageList(Long channelId, PageQuery pageQuery);

    /**
     * 查询线程已绑定的会话。
     *
     * @param channelId 渠道主键
     * @param threadKey 线程标识
     * @return 映射记录；不存在返回 {@code null}
     */
    AiChannelThread findThread(Long channelId, String threadKey);

    /**
     * 绑定线程到会话。
     *
     * @param channelId 渠道主键
     * @param threadKey 线程标识
     * @param sessionId 会话主键
     */
    void bindThread(Long channelId, String threadKey, Long sessionId);

    /**
     * 刷新线程最后活跃时间。
     *
     * @param channelId 渠道主键
     * @param threadKey 线程标识
     */
    void touchThread(Long channelId, String threadKey);

    /**
     * 删除消息线程映射（物理删除）。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    Boolean deleteThreads(Collection<Long> ids);

}