package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.core.channel.AiChannelRuntimeRegistry;
import org.dromara.ai.domain.bo.AiChannelBo;
import org.dromara.ai.domain.bo.AiChannelIdentityBo;
import org.dromara.ai.domain.vo.AiAgentVo;
import org.dromara.ai.domain.vo.AiChannelIdentityVo;
import org.dromara.ai.domain.vo.AiChannelThreadVo;
import org.dromara.ai.domain.vo.AiChannelVo;
import org.dromara.ai.domain.vo.ChannelUserLookupVo;
import org.dromara.ai.service.IAiAgentService;
import org.dromara.ai.service.IAiChannelService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI 渠道接入 Controller（REQ-AI-24）
 *
 * <p>本控制器只承载**管理面**（渠道配置 / 外部身份映射 / 消息线程与会话映射）；
 * 外部平台的消息入站走免登录的 {@code /ai/channel/callback/**}（见 {@code AiChannelCallbackController}）。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/channel")
public class AiChannelController extends BaseController {

    private final IAiChannelService aiChannelService;

    private final IAiAgentService aiAgentService;

    private final AiChannelRuntimeRegistry runtimeRegistry;

    /**
     * 查询渠道列表
     */
    @SaCheckPermission("ai:channel:list")
    @GetMapping("/list")
    public R<PageResult<AiChannelVo>> list(@Validated(QueryGroup.class) AiChannelBo bo, PageQuery pageQuery) {
        return R.ok(aiChannelService.queryPageList(bo, pageQuery));
    }

    /**
     * 查询启用中的智能体选项（供渠道选择默认智能体）
     */
    @SaCheckPermission("ai:channel:list")
    @GetMapping("/agent/options")
    public R<List<AiAgentVo>> agentOptions() {
        return R.ok(aiAgentService.queryOptions());
    }

    /**
     * 获取渠道详情
     *
     * @param id 主键
     */
    @SaCheckPermission("ai:channel:query")
    @GetMapping("/{id}")
    public R<AiChannelVo> getInfo(@NotNull(message = "主键不能为空")
                                  @PathVariable("id") Long id) {
        return R.ok(aiChannelService.queryById(id));
    }

    /**
     * 按手机号查询外部用户标识（辅助绑定「外部身份映射」；仅钉钉 / 飞书渠道支持）
     *
     * @param id     渠道主键
     * @param mobile 11 位手机号
     */
    @SaCheckPermission("ai:channel:query")
    @GetMapping("/{id}/external-user-id")
    public R<ChannelUserLookupVo> externalUserId(@NotNull(message = "主键不能为空")
                                                 @PathVariable("id") Long id,
                                                 @NotBlank(message = "手机号不能为空")
                                                 @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
                                                 @RequestParam("mobile") String mobile) {
        return R.ok(aiChannelService.lookupExternalUserId(id, mobile));
    }

    /**
     * 新增渠道
     */
    @SaCheckPermission("ai:channel:add")
    @Log(title = "AI渠道接入", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody AiChannelBo bo) {
        boolean ok = aiChannelService.insertByBo(bo);
        if (ok) {
            // 长连接平台（钉钉）需在保存后立即按新配置建立连接
            runtimeRegistry.refresh(bo.getChannelCode());
        }
        return toAjax(ok);
    }

    /**
     * 修改渠道（凭据留空表示保持原值）
     */
    @SaCheckPermission("ai:channel:edit")
    @Log(title = "AI渠道接入", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody AiChannelBo bo) {
        boolean ok = aiChannelService.updateByBo(bo);
        if (ok) {
            // 凭据 / 启停变更后按新配置重建装配（钉钉会重连长连接）
            runtimeRegistry.refresh(bo.getChannelCode());
        }
        return toAjax(ok);
    }

    /**
     * 删除渠道（逻辑删除；关联的身份映射与线程映射一并清理）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:channel:remove")
    @Log(title = "AI渠道接入", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        // 先取渠道编码，删除后据此停掉运行期连接（钉钉长连接需显式断开）
        List<String> channelCodes = new ArrayList<>();
        for (Long id : ids) {
            channelCodes.add(aiChannelService.queryById(id).getChannelCode());
        }
        boolean ok = aiChannelService.deleteWithValidByIds(Arrays.asList(ids));
        if (ok) {
            channelCodes.forEach(runtimeRegistry::evict);
        }
        return toAjax(ok);
    }

    // -----------------------------------------------------------------
    //  外部身份映射
    // -----------------------------------------------------------------

    /**
     * 查询外部身份映射列表
     */
    @SaCheckPermission("ai:channel:list")
    @GetMapping("/identity/list")
    public R<PageResult<AiChannelIdentityVo>> identityList(@Validated(QueryGroup.class) AiChannelIdentityBo bo,
                                                          PageQuery pageQuery) {
        return R.ok(aiChannelService.queryIdentityPageList(bo, pageQuery));
    }

    /**
     * 新增外部身份映射
     */
    @SaCheckPermission("ai:channel:add")
    @Log(title = "AI渠道身份映射", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/identity")
    public R<Void> identityAdd(@Validated(AddGroup.class) @RequestBody AiChannelIdentityBo bo) {
        return toAjax(aiChannelService.insertIdentity(bo));
    }

    /**
     * 修改外部身份映射
     */
    @SaCheckPermission("ai:channel:edit")
    @Log(title = "AI渠道身份映射", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/identity")
    public R<Void> identityEdit(@Validated(EditGroup.class) @RequestBody AiChannelIdentityBo bo) {
        return toAjax(aiChannelService.updateIdentity(bo));
    }

    /**
     * 删除外部身份映射
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:channel:remove")
    @Log(title = "AI渠道身份映射", businessType = BusinessType.DELETE)
    @DeleteMapping("/identity/{ids}")
    public R<Void> identityRemove(@NotEmpty(message = "主键不能为空")
                                  @PathVariable Long[] ids) {
        return toAjax(aiChannelService.deleteIdentities(Arrays.asList(ids)));
    }

    // -----------------------------------------------------------------
    //  消息线程与会话映射
    // -----------------------------------------------------------------

    /**
     * 查询消息线程与会话映射列表
     *
     * @param channelId 渠道主键（可选）
     */
    @SaCheckPermission("ai:channel:list")
    @GetMapping("/thread/list")
    public R<PageResult<AiChannelThreadVo>> threadList(@RequestParam(value = "channelId", required = false) Long channelId,
                                                       PageQuery pageQuery) {
        return R.ok(aiChannelService.queryThreadPageList(channelId, pageQuery));
    }

    /**
     * 删除消息线程与会话映射（物理删除；不影响历史会话）
     *
     * @param ids 主键串
     */
    @SaCheckPermission("ai:channel:remove")
    @Log(title = "AI渠道会话映射", businessType = BusinessType.DELETE)
    @DeleteMapping("/thread/{ids}")
    public R<Void> threadRemove(@NotEmpty(message = "主键不能为空")
                                @PathVariable Long[] ids) {
        return toAjax(aiChannelService.deleteThreads(Arrays.asList(ids)));
    }

}