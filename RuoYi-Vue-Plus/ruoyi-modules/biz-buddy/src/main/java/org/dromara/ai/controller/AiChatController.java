package org.dromara.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.ai.config.AiProperties;
import org.dromara.ai.core.agent.AiChatFacade;
import org.dromara.ai.domain.AiMessage;
import org.dromara.ai.domain.bo.AiChatAttachmentBo;
import org.dromara.ai.domain.bo.AiChatConfirmBo;
import org.dromara.ai.domain.bo.AiChatPinExpertBo;
import org.dromara.ai.domain.bo.AiChatSendBo;
import org.dromara.ai.domain.bo.AiSessionBo;
import org.dromara.ai.domain.bo.AiSessionResourceBatchBo;
import org.dromara.ai.domain.vo.AiChatAttachmentVo;
import org.dromara.ai.domain.vo.AiChatPendingVo;
import org.dromara.ai.domain.vo.AiMessageVo;
import org.dromara.ai.domain.vo.AiRegenerateCheckVo;
import org.dromara.ai.domain.vo.AiRouteRunDetailVo;
import org.dromara.ai.domain.vo.AiSessionResourceVo;
import org.dromara.ai.domain.vo.AiSessionVo;
import org.dromara.ai.domain.vo.AiToolCallLogVo;
import org.dromara.ai.service.IAiChatAttachmentService;
import org.dromara.ai.service.IAiRouteService;
import org.dromara.ai.service.IAiSessionResourceService;
import org.dromara.ai.service.IAiSessionService;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.validate.QueryGroup;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.redis.annotation.RateLimiter;
import org.dromara.common.web.core.BaseController;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 对话工作台 Controller
 *
 * <p>发消息拆成两步：先 POST 落库用户消息拿到稳定的 messageId，再按该 messageId 建立 SSE 连接取回答，
 * 避免把提问正文放进 URL。</p>
 *
 * @author BizBuddy
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/chat")
@SaCheckPermission("ai:chat:send")
public class AiChatController extends BaseController {

    private final IAiSessionService aiSessionService;

    /**
     * 会话级资源集（REQ-AI-32）：任务工作台「资源选择器」的读写
     */
    private final IAiSessionResourceService aiSessionResourceService;

    /**
     * 编排运行查询（REQ-AI-30）：会话的编排轨迹与专家调用明细
     */
    private final IAiRouteService aiRouteService;

    private final AiChatFacade aiChatFacade;

    private final AiProperties aiProperties;

    /**
     * 对话附件（REQ：聊天上传图片/文档）：校验、落库、解析与绑定
     */
    private final IAiChatAttachmentService aiChatAttachmentService;

    /**
     * 创建会话
     *
     * @param agentId        智能体主键
     * @param scenarioPackId 所选场景包主键（可选；为空表示不选包，该会话不接入任何 MCP 工具）
     * @param title          会话标题，留空时自动生成
     * @param pinnedExpertId 指定专家主键（可选；REQ-AI-31：非空表示本会话强制路由到该专家）
     */
    @PostMapping("/session")
    public R<AiSessionVo> createSession(@NotNull(message = "智能体不能为空") @RequestParam Long agentId,
                                        @RequestParam(required = false) Long scenarioPackId,
                                        @RequestParam(required = false) String title,
                                        @RequestParam(required = false) Long pinnedExpertId) {
        return R.ok(aiSessionService.createSession(agentId, scenarioPackId, title, pinnedExpertId));
    }

    /**
     * 查询会话详情（REQ-AI-31：会话头按 {@code pinnedExpertId} 展示「已指定：X」）
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}")
    public R<AiSessionVo> getSession(@NotNull(message = "会话不能为空")
                                     @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiSessionService.getOwnedSessionVo(sessionId));
    }

    /**
     * 指定 / 取消指定专家（REQ-AI-31）
     *
     * <p>请求体 {@code {expertAgentId: 123 | null}}：传主键为指定，传 {@code null} 为取消指定。
     * 与场景包不同，本项**会话内可变**，指定 / 取消都不会中断会话上下文。</p>
     *
     * @param sessionId 会话主键
     * @param bo        指定专家参数
     */
    @PutMapping("/session/{sessionId}/expert")
    public R<Void> pinExpert(@NotNull(message = "会话不能为空")
                             @PathVariable("sessionId") Long sessionId,
                             @RequestBody(required = false) AiChatPinExpertBo bo) {
        return toAjax(aiSessionService.setPinnedExpert(sessionId, bo == null ? null : bo.getExpertAgentId()));
    }

    /**
     * 查询会话级资源集（REQ-AI-32，含展示名，供标签 chip 渲染）
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}/resources")
    public R<List<AiSessionResourceVo>> listResources(@NotNull(message = "会话不能为空")
                                                      @PathVariable("sessionId") Long sessionId) {
        aiSessionService.getOwnedSession(sessionId);
        return R.ok(aiSessionResourceService.queryResources(sessionId));
    }

    /**
     * 批量增（REQ-AI-32）：重复项幂等；场景行与直接选中项各自独立，互不覆盖
     *
     * @param sessionId 会话主键
     * @param bo        资源项集合
     */
    @PostMapping("/session/{sessionId}/resources")
    public R<Void> addResources(@NotNull(message = "会话不能为空")
                                @PathVariable("sessionId") Long sessionId,
                                @RequestBody(required = false) AiSessionResourceBatchBo bo) {
        aiSessionService.getOwnedSession(sessionId);
        return toAjax(aiSessionResourceService.addResources(sessionId, bo));
    }

    /**
     * 批量删（REQ-AI-32）：逻辑删除即「移除」；{@code scenario} 行删除**不级联**删除已展开的单个资源
     *
     * @param sessionId 会话主键
     * @param bo        资源项集合
     */
    @DeleteMapping("/session/{sessionId}/resources")
    public R<Void> removeResources(@NotNull(message = "会话不能为空")
                                   @PathVariable("sessionId") Long sessionId,
                                   @RequestBody(required = false) AiSessionResourceBatchBo bo) {
        aiSessionService.getOwnedSession(sessionId);
        return toAjax(aiSessionResourceService.removeResources(sessionId, bo));
    }

    /**
     * 查询我的会话列表
     */
    @GetMapping("/session/list")
    public R<PageResult<AiSessionVo>> listSession(@Validated(QueryGroup.class) AiSessionBo bo, PageQuery pageQuery) {
        return R.ok(aiSessionService.queryMySessions(bo, pageQuery));
    }

    /**
     * 查询会话消息
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}/messages")
    public R<List<AiMessageVo>> listMessage(@NotNull(message = "会话不能为空")
                                            @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiSessionService.queryMessages(sessionId));
    }

    /**
     * 查询会话的工具调用明细（供对话页还原历史工具卡片）
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}/toolCalls")
    public R<List<AiToolCallLogVo>> listToolCalls(@NotNull(message = "会话不能为空")
                                                  @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiSessionService.queryOwnedToolCalls(sessionId));
    }

    /**
     * 查询会话的编排轨迹（REQ-AI-30）
     *
     * <p>一次编排一行，含决策原文、任务片段、专家调用次数与状态，以及每次专家调用的
     * 契约 / 校验错误 / 耗时 / Token / **原始输出原文**；前端「专家来源卡片」即取
     * {@code expertCalls[].rawOutput} 展示。</p>
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}/routeRuns")
    public R<List<AiRouteRunDetailVo>> listRouteRuns(@NotNull(message = "会话不能为空")
                                                     @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiRouteService.querySessionRuns(sessionId));
    }

    /**
     * 发送用户消息（先落库，返回带 messageId 的消息）
     *
     * <p>此处做对话限流：窗口内超限直接拒绝，且发生在落库之前，避免产生「有提问无回答」的脏数据。</p>
     *
     * @param sessionId 会话主键
     * @param bo        消息内容
     */
    @RateLimiter(key = "ai:chat:send", time = 60, count = 20, message = "提问过于频繁，请稍后再试")
    @PostMapping("/session/{sessionId}/message")
    public R<AiMessageVo> sendMessage(@NotNull(message = "会话不能为空")
                                      @PathVariable("sessionId") Long sessionId,
                                      @Validated @RequestBody AiChatSendBo bo) {
        AiMessage message = aiSessionService.appendUserMessage(sessionId, bo.getContent(), bo.getAttachmentIds());
        AiMessageVo vo = new AiMessageVo();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setCreateTime(message.getCreateTime());
        vo.setAttachments(aiChatAttachmentService.listVoMapByMessageIds(List.of(message.getId()))
            .get(message.getId()));
        return R.ok(vo);
    }

    /**
     * 登记聊天附件（图片 / 文档）
     *
     * <p>文件本体先经框架 OSS 上传接口（{@code /resource/oss/upload}）落库，这里按 {@code ossId}
     * 校验类型与大小，文档解析为纯文本后返回附件信息；发送消息时再以 {@code attachmentIds} 绑定。</p>
     *
     * @param sessionId 会话主键
     * @param bo        附件登记参数（ossId + 文件名）
     */
    @PostMapping("/session/{sessionId}/attachment")
    public R<AiChatAttachmentVo> registerAttachment(@NotNull(message = "会话不能为空")
                                                    @PathVariable("sessionId") Long sessionId,
                                                    @Validated @RequestBody AiChatAttachmentBo bo) {
        aiSessionService.getOwnedSession(sessionId);
        return R.ok(aiChatAttachmentService.register(sessionId, bo));
    }

    /**
     * 重新签发附件访问地址（打开原图 / 下载文档前调用）。
     *
     * <p>历史回显的附件地址是**预签名 URL**（默认 120 秒有效）：页面停留超时后再打开会 403
     * （MinIO `Request has expired`），故前端在点击时先换一个新签地址再打开。</p>
     *
     * @param sessionId    会话主键
     * @param attachmentId 附件主键
     */
    @GetMapping("/session/{sessionId}/attachment/{attachmentId}/url")
    public R<AiChatAttachmentVo> refreshAttachmentUrl(@NotNull(message = "会话不能为空")
                                                      @PathVariable("sessionId") Long sessionId,
                                                      @NotNull(message = "附件不能为空")
                                                      @PathVariable("attachmentId") Long attachmentId) {
        aiSessionService.getOwnedSession(sessionId);
        return R.ok(aiChatAttachmentService.refreshUrl(sessionId, attachmentId));
    }

    /**
     * 删除尚未发送的暂存附件（用户移除 chip 时调用）
     *
     * @param sessionId    会话主键
     * @param attachmentId 附件主键
     */
    @DeleteMapping("/session/{sessionId}/attachment/{attachmentId}")
    public R<Void> deleteAttachment(@NotNull(message = "会话不能为空")
                                    @PathVariable("sessionId") Long sessionId,
                                    @NotNull(message = "附件不能为空")
                                    @PathVariable("attachmentId") Long attachmentId) {
        aiChatAttachmentService.deletePending(sessionId, attachmentId);
        return R.ok();
    }

    /**
     * 删除一问一答（整轮）：该用户消息及其助手回复
     *
     * <p>只删平台侧记录，不影响模型上下文（框架会话记忆按会话整体存放，无法只摘一轮）。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 用户消息主键
     */
    @DeleteMapping("/session/{sessionId}/message/{messageId}")
    public R<Void> deleteMessage(@NotNull(message = "会话不能为空")
                                 @PathVariable("sessionId") Long sessionId,
                                 @NotNull(message = "消息不能为空")
                                 @PathVariable("messageId") Long messageId) {
        return toAjax(aiSessionService.deleteChatRound(sessionId, messageId));
    }

    /**
     * 记录用户对「需人工确认」工具调用的处置结果
     *
     * @param sessionId 会话主键
     * @param bo        确认参数
     */
    @PostMapping("/session/{sessionId}/confirm")
    public R<Void> confirm(@NotNull(message = "会话不能为空")
                           @PathVariable("sessionId") Long sessionId,
                           @Validated @RequestBody AiChatConfirmBo bo) {
        aiChatFacade.confirm(sessionId, bo);
        return R.ok();
    }

    /**
     * 查询会话内未决的工具确认（供刷新页面后恢复确认卡片）
     *
     * @param sessionId 会话主键
     */
    @GetMapping("/session/{sessionId}/pending")
    public R<List<AiChatPendingVo>> listPending(@NotNull(message = "会话不能为空")
                                                @PathVariable("sessionId") Long sessionId) {
        return R.ok(aiChatFacade.listPending(sessionId));
    }

    /**
     * 停止该会话当前正在进行的流式执行（对话页「停止」按钮）
     *
     * @param sessionId 会话主键
     */
    @PostMapping("/session/{sessionId}/stop")
    public R<Void> stop(@NotNull(message = "会话不能为空")
                        @PathVariable("sessionId") Long sessionId) {
        aiChatFacade.stop(sessionId);
        return R.ok();
    }

    /**
     * 建立 SSE 流取助手回答
     *
     * @param sessionId     会话主键
     * @param messageId     已落库的用户消息主键
     * @param response      当前响应
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@NotNull(message = "会话不能为空") @RequestParam Long sessionId,
                             @NotNull(message = "消息不能为空") @RequestParam Long messageId,
                             HttpServletResponse response) {
        prepareSseResponse(response);
        SseEmitter emitter = new SseEmitter(aiProperties.getChat().getStreamTimeoutMs());
        try {
            aiChatFacade.streamChat(sessionId, messageId, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 建立 SSE 流恢复被 HITL 暂停的一轮对话
     *
     * @param sessionId 会话主键
     * @param messageId 暂停时落库的助手消息主键
     * @param response  当前响应
     */
    @GetMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@NotNull(message = "会话不能为空") @RequestParam Long sessionId,
                             @NotNull(message = "消息不能为空") @RequestParam Long messageId,
                             HttpServletResponse response) {
        prepareSseResponse(response);
        SseEmitter emitter = new SseEmitter(aiProperties.getChat().getStreamTimeoutMs());
        try {
            aiChatFacade.streamResume(sessionId, messageId, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 重新生成预检：校验是否允许重跑，以及该轮是否发生过写操作。
     *
     * <p>前端据此决定「重新生成」按钮是否可用，并在禁用时展示原因与命中的写工具。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 本轮用户消息主键
     */
    @GetMapping("/session/{sessionId}/message/{messageId}/regenerate/check")
    public R<AiRegenerateCheckVo> checkRegenerate(@NotNull(message = "会话不能为空")
                                                  @PathVariable("sessionId") Long sessionId,
                                                  @NotNull(message = "消息不能为空")
                                                  @PathVariable("messageId") Long messageId) {
        return R.ok(aiChatFacade.checkRegenerable(sessionId, messageId));
    }

    /**
     * 重新生成最后一轮回答（只读轮次）
     *
     * <p>写操作轮次在预检阶段即被拒绝：通知发布、数据变更等副作用不允许因重跑而重复执行。</p>
     *
     * @param sessionId 会话主键
     * @param messageId 本轮用户消息主键
     * @param response  当前响应
     */
    @RateLimiter(key = "ai:chat:regenerate", time = 60, count = 10, message = "重新生成过于频繁，请稍后再试")
    @GetMapping(value = "/session/{sessionId}/message/{messageId}/regenerate",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@NotNull(message = "会话不能为空")
                                 @PathVariable("sessionId") Long sessionId,
                                 @NotNull(message = "消息不能为空")
                                 @PathVariable("messageId") Long messageId,
                                 HttpServletResponse response) {
        // 先做一次同步预检：拒绝时直接返回 JSON 错误，不建立 SSE 连接
        AiRegenerateCheckVo check = aiChatFacade.checkRegenerable(sessionId, messageId);
        if (!check.isAllowed()) {
            throw new ServiceException(check.getReason());
        }
        prepareSseResponse(response);
        SseEmitter emitter = new SseEmitter(aiProperties.getChat().getStreamTimeoutMs());
        try {
            aiChatFacade.regenerate(sessionId, messageId, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 终止会话
     *
     * @param sessionId 会话主键
     */
    @DeleteMapping("/session/{sessionId}")
    public R<Void> closeSession(@NotNull(message = "会话不能为空")
                                @PathVariable("sessionId") Long sessionId) {
        return toAjax(aiSessionService.closeSession(sessionId));
    }

    /**
     * 重命名会话
     *
     * @param sessionId 会话主键
     * @param title     新标题
     */
    @PutMapping("/session/{sessionId}/title")
    public R<Void> renameSession(@NotNull(message = "会话不能为空")
                                 @PathVariable("sessionId") Long sessionId,
                                 @NotBlank(message = "会话标题不能为空") @RequestParam String title) {
        return toAjax(aiSessionService.renameSession(sessionId, title));
    }

    /**
     * 置顶 / 取消置顶会话
     *
     * @param sessionId 会话主键
     * @param top       是否置顶
     */
    @PutMapping("/session/{sessionId}/top")
    public R<Void> topSession(@NotNull(message = "会话不能为空")
                              @PathVariable("sessionId") Long sessionId,
                              @NotNull(message = "置顶状态不能为空") @RequestParam Boolean top) {
        return toAjax(aiSessionService.toggleTop(sessionId, top));
    }

    /**
     * 设置 SSE 响应头。
     *
     * @param response 当前响应
     */
    private void prepareSseResponse(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
    }

}
