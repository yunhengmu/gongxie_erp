<template>
  <div ref="workbenchRef" class="chat-workbench p-2 app-container" :style="workbenchStyle">
    <!-- 左侧：会话列表 -->
    <aside class="chat-sidebar">
      <div class="sidebar-top">
        <el-button type="primary" icon="Plus" class="new-session-btn" @click="handleNewSession">新建任务</el-button>
      </div>

      <div class="session-list">
        <el-empty v-if="!sessions.length" description="暂无任务" :image-size="70" />
        <div
          v-for="item in sessions"
          :key="String(item.id)"
          class="session-item"
          :class="{ 'is-active': item.id === currentSessionId }"
          @click="handleSelectSession(item)"
        >
          <div class="session-item__main">
            <div class="session-item__title">
              <el-icon v-if="item.topFlag === '1'" class="session-item__pin"><Top /></el-icon>
              <span class="session-item__text">{{ item.sessionTitle || '未命名任务' }}</span>
            </div>
            <div class="session-item__time">{{ item.lastActiveTime || item.createTime }}</div>
          </div>
          <el-dropdown trigger="click" placement="bottom-end" @command="(command) => handleMoreCommand(command, item)">
            <el-icon class="session-item__more" @click.stop><MoreFilled /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="top">
                  {{ item.topFlag === '1' ? '取消置顶' : '置顶任务' }}
                </el-dropdown-item>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </aside>

    <!-- 右侧：会话内容 -->
    <section class="chat-main">
      <div ref="messageContainerRef" class="message-area" :class="{ 'message-area--empty': !messages.length }">
        <template v-for="msg in messages" :key="String(msg.id)">
          <div v-if="msg.role === 'user'" class="msg-row msg-row--user">
            <div class="msg-block msg-block--user">
              <div v-if="msg.attachments?.length" class="msg-attachments">
                <template v-for="att in msg.attachments" :key="String(att.id)">
                  <div
                    v-if="att.fileType === 'image' && att.url"
                    class="msg-attachment-image-wrap"
                    role="button"
                    tabindex="0"
                    title="查看原图"
                    @click="handlePreviewAttachment(att)"
                    @keydown.enter.prevent="handlePreviewAttachment(att)"
                  >
                    <el-image class="msg-attachment-image" :src="att.url" fit="cover" />
                  </div>
                  <a
                    v-else-if="att.url"
                    class="msg-attachment-file"
                    :href="att.url"
                    target="_blank"
                    rel="noopener"
                    :title="att.fileName"
                    @click.prevent="handleOpenAttachment(att)"
                  >
                    <el-icon><Document /></el-icon>
                    <span class="msg-attachment-file__name">{{ att.fileName }}</span>
                  </a>
                  <span v-else class="msg-attachment-file">
                    <el-icon><Document /></el-icon>
                    <span class="msg-attachment-file__name">{{ att.fileName }}</span>
                  </span>
                </template>
              </div>
              <div v-if="msg.content" class="bubble bubble--user">{{ msg.content }}</div>
              <!-- 悬停或键盘聚焦时显示：发出时间 + 复制 + 删除（整轮删除该提问及其回答） -->
              <div class="msg-actions">
                <span class="msg-actions__time">{{ formatMessageTime(msg.createTime) }}</span>
                <el-icon
                  class="msg-actions__icon"
                  role="button"
                  tabindex="0"
                  title="复制"
                  @click="handleCopyMessage(msg)"
                  @keydown.enter.prevent="handleCopyMessage(msg)"
                >
                  <DocumentCopy />
                </el-icon>
                <el-icon
                  class="msg-actions__icon"
                  role="button"
                  tabindex="0"
                  title="删除"
                  @click="handleDeleteMessage(msg)"
                  @keydown.enter.prevent="handleDeleteMessage(msg)"
                >
                  <Delete />
                </el-icon>
              </div>
            </div>
          </div>

          <div v-else-if="msg.role === 'assistant'" class="msg-row msg-row--assistant">
            <div class="avatar">
              <img class="avatar__logo" :src="logo" alt="小Z" />
            </div>
            <div class="bubble bubble--assistant">
              <template v-for="(part, index) in msg.parts" :key="index">
                <AiMarkdown v-if="part.kind === 'text'" :content="part.text ?? ''" />
                <div v-else-if="part.kind === 'tool'" class="tool-card">
                  <div
                    class="tool-card__head"
                    role="button"
                    tabindex="0"
                    :aria-expanded="isToolExpanded(part)"
                    @click="toggleTool(part)"
                    @keydown.enter.prevent="toggleTool(part)"
                    @keydown.space.prevent="toggleTool(part)"
                  >
                    <el-icon class="tool-card__arrow" :class="{ 'is-expanded': isToolExpanded(part) }">
                      <ArrowRight />
                    </el-icon>
                    <el-icon class="tool-card__icon"><Tools /></el-icon>
                    <span class="tool-card__name">{{ part.toolName }}</span>
                    <el-tag v-if="part.source" type="warning" effect="plain" size="small">
                      子智能体：{{ part.source.split('/').pop() }}
                    </el-tag>
                    <el-tag :type="toolStateType(part.state)" size="small" effect="plain">
                      {{ toolStateLabel(part.state) }}
                    </el-tag>
                    <span v-if="part.durationMs !== undefined && part.durationMs !== null" class="tool-card__meta">
                      {{ part.durationMs }} ms
                    </span>
                  </div>
                  <div v-show="isToolExpanded(part)" class="tool-card__body">
                    <div class="tool-card__section">
                      <div class="tool-card__label">调用入参</div>
                      <pre class="tool-code">{{ part.input || '{}' }}</pre>
                    </div>
                    <div v-if="part.output" class="tool-card__section">
                      <div class="tool-card__label">结果摘要</div>
                      <pre class="tool-code">{{ part.output }}</pre>
                    </div>
                  </div>
                </div>
                <div v-else-if="part.kind === 'expert'" class="expert-card">
                  <div
                    class="expert-card__head"
                    role="button"
                    tabindex="0"
                    :aria-expanded="isExpertExpanded(part)"
                    @click="toggleExpert(part)"
                    @keydown.enter.prevent="toggleExpert(part)"
                  >
                    <el-icon class="expert-card__arrow" :class="{ 'is-expanded': isExpertExpanded(part) }">
                      <ArrowRight />
                    </el-icon>
                    <el-icon class="expert-card__icon"><UserFilled /></el-icon>
                    <span class="expert-card__name">{{ part.expertName || part.expertKey || '领域专家' }}</span>
                    <el-tag v-if="part.attemptNo && part.attemptNo > 1" type="warning" size="small" effect="plain">
                      第 {{ part.attemptNo }} 次
                    </el-tag>
                    <el-tag :type="expertStateType(part.expertState)" size="small" effect="plain">
                      {{ expertStateLabel(part.expertState) }}
                    </el-tag>
                  </div>
                  <div v-if="part.expertError" class="expert-card__error">
                    <el-icon><WarningFilled /></el-icon>
                    <span>{{ part.expertError }}</span>
                  </div>
                  <div v-show="isExpertExpanded(part)" class="expert-card__body">
                    <div class="tool-card__label">专家原始结论（原文留证）</div>
                    <pre class="tool-code">{{ part.expertConclusion || '（暂无可展示的结论原文）' }}</pre>
                  </div>
                </div>
                <div v-else-if="part.kind === 'route'" class="route-note">
                  <el-icon class="route-note__icon"><Share /></el-icon>
                  <span>{{ part.reason }}</span>
                </div>
                <div v-else class="confirm-card" :class="`confirm-card--${part.confirmState}`">
                  <div class="confirm-card__head">
                    <el-icon class="confirm-card__icon"><Lock /></el-icon>
                    <span class="confirm-card__title">该操作需要你确认后才会执行</span>
                    <el-tag :type="confirmStateType(part.confirmState)" size="small" effect="plain">
                      {{ confirmStateLabel(part.confirmState) }}
                    </el-tag>
                  </div>
                  <div v-for="call in part.toolCalls ?? []" :key="call.toolCallId" class="confirm-card__item">
                    <!-- 展示中文名（后端下发 toolLabel，查不到回退工具编码）：
                         用户要据此决定放行与否，工具编码（如 create_notice）看不懂 -->
                    <div class="confirm-card__tool">{{ call.toolLabel || call.toolName }}</div>
                    <!-- 一句话说明该工具做什么，降低「看不懂就盲点允许」的风险 -->
                    <div v-if="call.toolDesc" class="confirm-card__desc">{{ call.toolDesc }}</div>
                    <!-- 原始入参仅在待确认时展示：已处置卡片只需保留结论（状态/拒绝原因），
                         避免历史消息里堆积大段 JSON、淹没结论 -->
                    <pre v-if="part.confirmState === 'pending'" class="tool-code">{{ formatToolInput(call.input) }}</pre>
                  </div>
                  <div v-if="part.confirmState === 'pending'" class="confirm-card__actions">
                    <el-button type="primary" size="small" @click="handleConfirmTool(part, true, false)">
                      允许本次
                    </el-button>
                    <el-button size="small" @click="handleConfirmTool(part, true, true)">记住该工具</el-button>
                    <el-button type="danger" size="small" plain @click="handleRejectTool(part)">
                      拒绝
                    </el-button>
                  </div>
                  <div v-else-if="part.confirmState === 'rejected'" class="confirm-card__tip">
                    已拒绝{{ part.confirmReason ? `：${part.confirmReason}` : '' }}。
                  </div>
                  <div v-else-if="part.confirmState === 'expired'" class="confirm-card__tip">
                    确认已失效（已过期或被新的确认请求替代），请重新提问。
                  </div>
                </div>
              </template>

              <span v-if="msg.streaming && !msg.phaseText" class="typing-cursor"></span>

              <!-- 对话期实时进度：长等待（模型思考 / 工具执行 / 专家调用）时播报「小Z 在干什么」，
                   正文一开始流动就让位给内容（见 handleStreamEvent 的 text_delta 分支） -->
              <div v-if="msg.phaseText" class="phase-bar">
                <span class="phase-bar__dot"></span>
                <span class="phase-bar__text">{{ msg.phaseText }}</span>
              </div>

              <!-- 参考来源：回答用到知识库时展示（默认收起，点击标题展开 / 收起） -->
              <div v-if="msg.sources && msg.sources.length" class="msg-sources">
                <div
                  class="msg-sources__head"
                  role="button"
                  tabindex="0"
                  :aria-expanded="isSourcesExpanded(msg)"
                  @click="toggleSources(msg)"
                  @keydown.enter.prevent="toggleSources(msg)"
                  @keydown.space.prevent="toggleSources(msg)"
                >
                  <el-icon class="msg-sources__arrow" :class="{ 'is-expanded': isSourcesExpanded(msg) }">
                    <ArrowRight />
                  </el-icon>
                  <span class="msg-sources__title">参考来源</span>
                </div>
                <div v-show="isSourcesExpanded(msg)" class="msg-sources__body">
                  <div
                    v-for="(source, index) in msg.sources"
                    :key="index"
                    class="msg-sources__item"
                    :class="{ 'is-clickable': hasSourceDoc(source) }"
                    :role="hasSourceDoc(source) ? 'button' : undefined"
                    :tabindex="hasSourceDoc(source) ? 0 : undefined"
                    :title="hasSourceDoc(source) ? '查看命中原文' : undefined"
                    @click="handleOpenSource(source)"
                    @keydown.enter.prevent="handleOpenSource(source)"
                  >
                    <el-tag :type="sourceScoreType(source.score)" size="small" effect="plain">
                      {{ formatSourceScore(source.score) }}
                    </el-tag>
                    <span class="msg-sources__text">
                      <el-icon class="msg-sources__icon"><Document /></el-icon>
                      {{ source.docName || '未知文档' }}
                      <template v-if="source.headingPath">· §{{ source.headingPath }}</template>
                      <template v-if="source.pageNo !== undefined && source.pageNo !== null">
                        · p.{{ source.pageNo }}
                      </template>
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="msg.status === '1'" class="msg-error">
                <el-icon><WarningFilled /></el-icon>
                <span>{{ msg.errorMsg || '回复失败' }}</span>
                <!-- 仅当该轮记录了可重发的正文/附件时才给重试入口（系统级错误无可重发内容） -->
                <el-button
                  v-if="msg.retryPayload"
                  type="primary"
                  link
                  size="small"
                  @click="handleRetryMessage(msg)"
                >
                  重新发送
                </el-button>
              </div>
              <div
                v-else-if="!msg.streaming && (msg.durationMs !== undefined || msg.status === '3')"
                class="msg-meta"
              >
                <!-- 复制图标：始终显示（左对齐） -->
                <el-icon
                  class="msg-meta__icon"
                  role="button"
                  tabindex="0"
                  title="复制"
                  @click="handleCopyMessage(msg)"
                  @keydown.enter.prevent="handleCopyMessage(msg)"
                >
                  <DocumentCopy />
                </el-icon>
                <!-- 重新生成：仅最后一轮可重跑；写操作轮次由预检置灰并给出原因 -->
                <el-button
                  v-if="canRegenerate(msg)"
                  class="msg-meta__regenerate"
                  type="primary"
                  link
                  size="small"
                  :disabled="regenerateBlocked"
                  :title="regenerateHint"
                  @click="handleRegenerate(msg)"
                >
                  <el-icon><Refresh /></el-icon>
                  重新生成
                </el-button>
                <!-- 中止标记：本地停止或后端 aborted 时给出明确反馈 -->
                <span v-if="msg.status === '3'" class="msg-meta__aborted">已中止</span>
                <!-- 文字区域：悬停回复内容才显示；整体前带一个分隔竖线 -->
                <span class="msg-meta__info">
                  <span v-if="msg.durationMs !== undefined">耗时 {{ msg.durationMs }} ms</span>
                  <span v-if="msg.toolCallCount">工具调用 {{ msg.toolCallCount }} 次</span>
                  <span v-if="msg.totalTokens">消耗 {{ msg.totalTokens }} tokens</span>
                </span>
                <!-- AI 生成声明：始终显示，右对齐 -->
                <span class="msg-meta__ai">由 AI 生成</span>
              </div>
            </div>
          </div>

          <div v-else class="msg-row msg-row--system">{{ msg.content }}</div>
        </template>
      </div>

      <!-- 底栏：输入区（对话前垂直居中，发出第一条消息后回到底部） -->
      <div class="composer" :class="{ 'composer--center': !messages.length }">
        <!-- 对话前标题：输入框上方约两行文字高度处，水平居中；发出第一条消息后移除 -->
        <div v-if="!messages.length" class="composer__title">BizBuddy, 我帮您</div>
        <el-input
          v-model="inputText"
          type="textarea"
          :autosize="{ minRows: 4.5, maxRows: 10 }"
          resize="none"
          :disabled="streaming || sending"
          placeholder="今天帮您干点什么？"
          @keydown="handleComposerKeydown"
        />
        <div class="composer-actions">
          <!-- 资源选择器（REQ-AI-32）：最左侧一个 +，按分组选资源；选中项以标签跟在 + 后面 -->
          <div class="resource-bar">
            <el-popover placement="top-start" :width="190" trigger="click" popper-class="resource-picker-popper">
              <template #reference>
                <el-button class="resource-add-btn" icon="Plus" circle title="选择资源或上传附件" />
              </template>
              <!-- 二级：分组列表；三级：悬浮飞出的选项面板（支持上下滚动） -->
              <div class="resource-menu">
                <!-- 上传附件：置于菜单顶部，与下方资源分组以分割线隔开 -->
                <div class="resource-menu__action" @click="handleTriggerAttachment">
                  <el-icon class="resource-menu__action-icon" :class="{ 'is-loading': uploadingAttachment }">
                    <Paperclip />
                  </el-icon>
                  <span class="resource-menu__action-label">上传附件</span>
                </div>
                <input
                  ref="attachmentInputRef"
                  type="file"
                  class="attachment-input"
                  :accept="ATTACHMENT_ACCEPT"
                  @change="handleAttachmentChange"
                />
                <div class="resource-menu__divider"></div>
                <div
                  v-for="group in resourceGroups"
                  :key="group.key"
                  class="resource-menu__group"
                  :class="{ 'is-active': activeGroup === group.key }"
                  @mouseenter="activeGroup = group.key"
                  @click="activeGroup = group.key"
                >
                  <span class="resource-menu__group-label">{{ group.label }}</span>
                  <span class="resource-menu__group-count">{{ group.count }}</span>
                  <el-icon class="resource-menu__arrow"><ArrowRight /></el-icon>

                  <div v-show="activeGroup === group.key" class="resource-flyout">
                    <div class="resource-flyout__title">{{ group.label }}</div>
                    <div class="resource-flyout__scroll">
                      <div v-if="!group.items.length" class="resource-flyout__empty">暂无可选项</div>
                      <template v-for="item in group.items" :key="item.key">
                        <div v-if="item.section" class="resource-flyout__section">{{ item.section }}</div>
                        <div
                          v-else
                          class="resource-flyout__item"
                          :class="{ 'is-selected': item.selected }"
                          @click.stop="item.command && handleResourceSelect(item.command)"
                        >
                          <span class="resource-flyout__item-label" :title="item.tip">{{ item.label }}</span>
                          <el-icon v-if="item.selected" class="resource-menu__check"><Check /></el-icon>
                        </div>
                      </template>
                    </div>
                  </div>
                </div>
              </div>
            </el-popover>
            <div v-if="resourceChips.length" class="resource-chips">
              <span
                v-for="chip in resourceChips"
                :key="chip.key"
                class="resource-chip"
                :class="`resource-chip--${chip.kind}`"
                :title="chip.tip"
              >
                <el-icon class="resource-chip__close" @click="handleRemoveChip(chip)"><Close /></el-icon>
                <span class="resource-chip__label">{{ chip.label }}</span>
              </span>
            </div>
          </div>
          <!-- 待发附件：选中项以标签跟在资源标签之后（上传入口已移入上方 + 菜单） -->
          <div v-if="pendingAttachments.length" class="attachment-bar">
            <div class="attachment-chips">
              <span
                v-for="att in pendingAttachments"
                :key="String(att.id)"
                class="attachment-chip"
                :class="`attachment-chip--${att.fileType}`"
                :title="att.fileName"
              >
                <el-icon class="attachment-chip__close" @click="handleRemoveAttachment(att)"><Close /></el-icon>
                <span class="attachment-chip__label">{{ att.fileName }}</span>
              </span>
            </div>
          </div>
          <div class="composer-actions-right">
            <span class="composer-tip">Enter 发送，Shift + Enter 换行</span>
            <el-button v-if="streaming" type="danger" icon="VideoPause" @click="handleStop">停止</el-button>
            <el-button v-else type="primary" icon="Promotion" :loading="sending" @click="handleSend">发送</el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- 原图预览：点击附件时先换一个新签地址再打开，避免预签名 URL 过期后 403 -->
    <el-image-viewer
      v-if="previewVisible"
      :url-list="[previewUrl]"
      teleported
      @close="previewVisible = false"
    />

    <!-- 参考来源定位：点击来源后按「文档 + 切片序号」现查原文，避免把整篇文档塞进消息记录 -->
    <el-drawer
      v-model="sourceDrawer.visible"
      :title="sourceDrawer.docName || '参考来源'"
      size="560px"
      append-to-body
      destroy-on-close
      class="source-drawer"
    >
      <div v-loading="sourceDrawer.loading" class="source-detail">
        <div v-if="sourceDrawer.meta" class="source-detail__meta">{{ sourceDrawer.meta }}</div>

        <!-- 全文模式：历史来源未记录切片序号、或用户想通读该文档时使用 -->
        <template v-if="sourceDrawer.showFullText">
          <div class="source-detail__toolbar">
            <el-button link type="primary" size="small" @click="sourceDrawer.showFullText = false">
              返回命中片段
            </el-button>
          </div>
          <div class="source-detail__fulltext">{{ sourceDrawer.fullText }}</div>
        </template>

        <template v-else-if="sourceDrawer.context">
          <div
            v-for="chunk in sourceDrawer.context.before"
            :key="'b-' + chunk.chunkIndex"
            class="source-detail__chunk source-detail__chunk--neighbor"
          >
            <div class="source-detail__label">第 {{ chunk.chunkIndex + 1 }} 段</div>
            <div class="source-detail__text">{{ chunk.content }}</div>
          </div>

          <div v-if="sourceDrawer.context.target" class="source-detail__chunk source-detail__chunk--target">
            <div class="source-detail__label">
              命中片段 · 第 {{ sourceDrawer.context.target.chunkIndex + 1 }} 段
            </div>
            <div class="source-detail__text">{{ sourceDrawer.context.target.content }}</div>
          </div>
          <el-empty
            v-else
            description="来源文档已变更（可能已重新解析），当前序号无法定位"
            :image-size="60"
          />

          <div
            v-for="chunk in sourceDrawer.context.after"
            :key="'a-' + chunk.chunkIndex"
            class="source-detail__chunk source-detail__chunk--neighbor"
          >
            <div class="source-detail__label">第 {{ chunk.chunkIndex + 1 }} 段</div>
            <div class="source-detail__text">{{ chunk.content }}</div>
          </div>

          <div class="source-detail__toolbar">
            <el-button
              v-hasPermi="['ai:kb:doc:preview']"
              link
              type="primary"
              size="small"
              @click="handleShowFullText"
            >查看文档全文</el-button>
          </div>
        </template>

        <el-empty
          v-else-if="!sourceDrawer.loading"
          :description="sourceDrawer.error || '暂无内容'"
          :image-size="60"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script setup name="AiAgentChat" lang="ts">
import { optionAgent, optionAgentExperts, optionAgentTool } from '@/api/ai/agent';
import type { AgentVO, ExpertOptionVO, ToolOptionVO } from '@/api/ai/agent/types';
import {
  addChatResources,
  buildChatRegenerateUrl,
  buildChatResumeUrl,
  buildChatStreamUrl,
  checkChatRegenerate,
  closeChatSession,
  confirmChatTool,
  createChatSession,
  deleteChatAttachment,
  deleteChatMessage,
  registerChatAttachment,
  listChatMessage,
  listChatPending,
  listChatResources,
  listChatRouteRuns,
  listChatSession,
  listChatToolCall,
  pinChatSessionExpert,
  removeChatResources,
  renameChatSession,
  refreshChatAttachmentUrl,
  sendChatMessage,
  stopChat,
  topChatSession
} from '@/api/ai/chat';
import type {
  AiRouteRunDetailVO,
  ChatAttachmentVO,
  ChatMessageVO,
  ChatPendingToolCall,
  ChatRegenerateCheckVO,
  ChatResourceItem,
  ChatResourceType,
  ChatResourceVO,
  ChatSessionVO,
  ChatSourceItem,
  ChatStreamEvent
} from '@/api/ai/chat/types';
import { getDocumentChunkContext, getDocumentContent } from '@/api/ai/kb/doc';
import type { ChunkContextVO } from '@/api/ai/kb/doc/types';
import { scenarioPackMcpOptions, scenarioPackOptions } from '@/api/ai/pack';
import type { ScenarioPackMcpOptionVO, ScenarioPackOptionVO } from '@/api/ai/pack/types';
import { visibleSkillOptions } from '@/api/ai/skill';
import type { SkillOptionVO } from '@/api/ai/skill/types';
import type { ToolCallLogVO } from '@/api/ai/session/types';
import { uploadOss } from '@/api/system/oss';
import cache from '@/plugins/cache';
import modal from '@/plugins/modal';
import logo from '@/assets/logo/logo.png';
import AiMarkdown from '@/views/ai/components/AiMarkdown.vue';

/** 消息片段：文本片段、工具调用卡片、人工确认卡片、编排提示与专家卡片交错排列 */
interface MessagePart {
  /** 片段类型 */
  kind: 'text' | 'tool' | 'confirm' | 'expert' | 'route';
  /** 文本内容（kind=text） */
  text?: string;
  /** 工具调用标识（kind=tool） */
  toolCallId?: string;
  /** 工具名（kind=tool） */
  toolName?: string;
  /** 子智能体来源（形如 main/<子智能体标识>；主智能体自身发起时为空） */
  source?: string;
  /** 调用状态（running / success / error） */
  state?: string;
  /** 工具入参 JSON（增量拼接） */
  input?: string;
  /** 结果摘要 */
  output?: string;
  /** 耗时（毫秒） */
  durationMs?: number;
  /** 待确认的助手消息主键（kind=confirm） */
  messageId?: string | number;
  /** 待确认的工具调用（kind=confirm） */
  toolCalls?: ChatPendingToolCall[];
  /** 确认状态（pending / approved / rejected / expired，kind=confirm） */
  confirmState?: 'pending' | 'approved' | 'rejected' | 'expired';
  /** 拒绝原因（kind=confirm，用户拒绝时填写，用于卡片回显） */
  confirmReason?: string;
  /** 专家标识（kind=expert，REQ-AI-30） */
  expertKey?: string;
  /** 专家智能体主键（kind=expert，用于按 agentId + attemptNo 挂回轨迹里的原始结论） */
  expertAgentId?: string | number;
  /** 专家原始结论 / 结构化结论文本（kind=expert，来自编排轨迹的 raw_output / parsed_output） */
  expertConclusion?: string;
  /** 专家名称（kind=expert） */
  expertName?: string;
  /** 第几次尝试（kind=expert） */
  attemptNo?: number;
  /** 调用类型（first 首次 / retry 同专家重试 / switch 换专家，kind=expert） */
  callKind?: string;
  /** 卡片状态（running 调用中 / success 通过契约 / invalid 结构不合格，kind=expert） */
  expertState?: 'running' | 'success' | 'invalid';
  /** 结构不合格说明（kind=expert） */
  expertError?: string;
  /** 编排提示文案（kind=route：小Z 的决策或降级说明） */
  reason?: string;
}

/** 本地渲染用消息 */
interface LocalMessage {
  id: string | number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  parts: MessagePart[];
  streaming: boolean;
  status: string;
  createTime?: string;
  errorMsg?: string;
  durationMs?: number;
  toolCallCount?: number;
  /** 总 Token 数（悬停时展示的消耗量） */
  totalTokens?: number;
  /** 随本条消息发送的附件（聊天上传的图片/文档） */
  attachments?: ChatAttachmentVO[];
  /** 回答参考来源（知识库命中；流式与历史消息均可见） */
  sources?: ChatSourceItem[];
  /** 失败轮次的重试载荷：记录本轮正文与附件，供「重新发送」原样重发 */
  retryPayload?: {
    /** 本轮用户正文 */
    content: string;
    /** 本轮用户消息主键（重试时先删除，避免历史里出现重复用户气泡） */
    userMessageId?: string | number;
    /** 本轮随消息发送的附件主键 */
    attachmentIds: Array<string | number>;
  };
  /** 对话期实时进度文案（后端 phase 事件下发；正文开始流动即清空） */
  phaseText?: string;
}

/** 资源标签（REQ-AI-32）：+ 后面显示的选中项，悬停左侧出叉号可移除 */
interface ResourceChip {
  /** 唯一键（v-for） */
  key: string;
  /** 分组类型 */
  kind: 'expert' | 'scenario' | 'tool' | 'skill' | 'mcp';
  /** 标签文案 */
  label: string;
  /** 悬停提示 */
  tip?: string;
  /** 移除时提交的资源项（专家走 pinned 接口，故为空） */
  item?: ChatResourceItem;
}

/** 资源选择器三级菜单的一项（`section` 非空时渲染为分组小标题，如 MCP 的 Server） */
interface ResourceMenuItem {
  /** 唯一键 */
  key: string;
  /** 展示文案 */
  label: string;
  /** 悬浮提示 */
  tip?: string;
  /** 是否已选中 */
  selected?: boolean;
  /** 分组小标题（MCP 按 Server 分组；非空时本项为标题行、不可点击） */
  section?: string;
  /** 选择指令（expert:/scenario:/tool:/skill:/mcp: 前缀，交 handleResourceSelect 分派） */
  command?: string;
}

/** 资源选择器的二级分组 */
interface ResourceMenuGroup {
  /** 分组标识（expert / scenario / tool / skill / mcp） */
  key: string;
  /** 分组名 */
  label: string;
  /** 可选项数量 */
  count: number;
  /** 三级菜单项 */
  items: ResourceMenuItem[];
}

const agentOptions = ref<AgentVO[]>([]);
const expertOptions = ref<ExpertOptionVO[]>([]);
const packOptions = ref<ScenarioPackOptionVO[]>([]);
const toolOptions = ref<ToolOptionVO[]>([]);
const skillOptions = ref<SkillOptionVO[]>([]);
const mcpOptions = ref<ScenarioPackMcpOptionVO[]>([]);
const sessions = ref<ChatSessionVO[]>([]);
const messages = ref<LocalMessage[]>([]);
/** 会话级资源集（REQ-AI-32）：已有会话时为服务端真值，未创建会话时为本地暂存 */
const sessionResources = ref<ChatResourceVO[]>([]);
/** 指定专家（会话级可空；空表示由小Z 判断，REQ-AI-31/32） */
const pinnedExpert = ref<{ id: string | number; name: string } | undefined>(undefined);
/** 当前展开三级菜单的分组（空＝未展开；鼠标悬停 / 点击二级分组时切换） */
const activeGroup = ref<string>('');
const currentSessionId = ref<string | number | undefined>(undefined);
const currentAssistantKey = ref<string | number | undefined>(undefined);
const inputText = ref('');
const streaming = ref(false);
const sending = ref(false);
/** 最后一轮重新生成预检结果（null 表示尚未检查或检查失败） */
const regenerateCheck = ref<ChatRegenerateCheckVO | null>(null);
/** 预检结果对应的用户消息主键，避免切换会话后误用旧结果 */
const regenerateCheckMessageId = ref<string | number | undefined>(undefined);
/** 重新生成失败回滚快照：后端在预检 / 上下文回退阶段拒绝时恢复旧回答 */
let regenerateBackup: LocalMessage[] | null = null;
const messageContainerRef = ref<HTMLElement>();
const streamUrl = ref<string | undefined>(undefined);
/** 待发送附件（登记成功后暂存，发送时随消息提交） */
const pendingAttachments = ref<ChatAttachmentVO[]>([]);
/** 附件上传中（回形针按钮 loading） */
const uploadingAttachment = ref(false);
/** 隐藏的文件选择框 */
const attachmentInputRef = ref<HTMLInputElement>();

/**
 * 「尚未落库的指定专家」本地缓存键：会话未创建时专家选择只在前端暂存，刷新即丢，
 * 故用会话级存储兜住这一窗口（见 savePendingExpertCache）。
 */
const PENDING_EXPERT_CACHE_KEY = 'ai-chat-pending-expert';

/** 单条消息可携带的附件数量上限（与后端 bizbuddy.ai.chat.max-attachments-per-message 保持一致） */
const MAX_ATTACHMENTS = 5;
/** 聊天附件可接受的类型（与后端一致：图片 + AiDocumentParser 支持的文档） */
const ATTACHMENT_ACCEPT =
  '.png,.jpg,.jpeg,.gif,.webp,.heic,.heif,.pdf,.docx,.xlsx,.pptx,.html,.htm,.txt,.md,.markdown,.csv,.tsv,.json,.xml,.yml,.yaml,.log,.sql,.properties,.ini,.conf,.java,.js,.ts,.py,.sh';

/** 工作台高度下限（可用高度不足时兜底，避免窄屏 / 小窗口下主区被压没） */
const MIN_WORKBENCH_HEIGHT = 360;

/** 工作台容器（用于实测高度） */
const workbenchRef = ref<HTMLElement>();

/** 工作台高度（按父容器实测；空串表示交给样式表，如窄屏堆叠布局） */
const workbenchHeight = ref('');

/** 工作台内联样式（仅在实测到高度时生效） */
const workbenchStyle = computed(() => (workbenchHeight.value ? { height: workbenchHeight.value } : {}));

/**
 * 按父容器实测可用高度：视口高度 − 容器顶部偏移 − 父容器底部内边距。
 *
 * <p>高度写死（原先 `calc(100vh - 100px)`）与「固定头部 + 标签页」的实际占位对不上时，
 * 容器会高于可视区，把文档撑高而出现页面级滚动条（进入页面即出现，且与内部消息区滚动条并存）。
 * 这里改为测量真实偏移，跟随固定头部 / 标签页 / 窗口尺寸变化自动适配。</p>
 */
const syncWorkbenchHeight = () => {
  const el = workbenchRef.value;
  if (!el) {
    return;
  }
  if (window.innerWidth <= 900) {
    // 窄屏为上下堆叠布局，高度交给样式表的媒体查询（height: auto）
    workbenchHeight.value = '';
    return;
  }
  let top = 0;
  let node: HTMLElement | null = el;
  while (node) {
    top += node.offsetTop;
    node = node.offsetParent as HTMLElement | null;
  }
  const host = el.closest('.app-main');
  const bottomPadding = host ? Number.parseFloat(window.getComputedStyle(host).paddingBottom) || 0 : 0;
  workbenchHeight.value = `${Math.max(MIN_WORKBENCH_HEIGHT, Math.floor(window.innerHeight - top - bottomPadding))}px`;
};

/** 挂载后校准的延迟任务（等待路由过渡与标签页 / 固定头部占位稳定） */
let workbenchTimers: number[] = [];

/** 观察父容器尺寸变化（标签页 / 固定头部开关会改变顶部占位） */
let workbenchResizeObserver: ResizeObserver | undefined;

onMounted(() => {
  syncWorkbenchHeight();
  workbenchTimers = [0, 120, 360].map((delay) => window.setTimeout(syncWorkbenchHeight, delay));
  window.addEventListener('resize', syncWorkbenchHeight);
  const host = workbenchRef.value?.closest('.app-main');
  if (host) {
    workbenchResizeObserver = new ResizeObserver(syncWorkbenchHeight);
    workbenchResizeObserver.observe(host);
  }
});

onActivated(() => {
  syncWorkbenchHeight();
});

onBeforeUnmount(() => {
  workbenchTimers.forEach((timer) => window.clearTimeout(timer));
  workbenchTimers = [];
  window.removeEventListener('resize', syncWorkbenchHeight);
  workbenchResizeObserver?.disconnect();
  workbenchResizeObserver = undefined;
});

// SSE 连接：手动 open，必须关闭自动重连，否则流结束后会重连重跑一轮
const {
  data: sseData,
  error: sseError,
  open: openStream,
  close: closeStream
} = useEventSource(streamUrl, [], { immediate: false, autoConnect: false, autoReconnect: false });

/** 当前正在流式输出的助手消息 */
const currentAssistant = () => messages.value.find((item) => item.id === currentAssistantKey.value);

/** 工具入参片段 */
const findToolPart = (msg: LocalMessage, toolCallId?: string) =>
  msg.parts.find((part) => part.kind === 'tool' && part.toolCallId === toolCallId);

/** 取最后一个「调用中」的同专家卡片（expert_result / expert_invalid 回填状态用，REQ-AI-30） */
const findRunningExpertPart = (msg: LocalMessage, expertKey?: string) => {
  for (let index = msg.parts.length - 1; index >= 0; index -= 1) {
    const part = msg.parts[index];
    if (part.kind === 'expert' && part.expertKey === expertKey && part.expertState === 'running') {
      return part;
    }
  }
  return undefined;
};

/** 专家卡片展开键（agentId#尝试次数，默认全部收起） */
const expandedExpertKeys = ref<string[]>([]);

/** 专家卡片展开键 */
const expertPartKey = (part: MessagePart) => `${part.expertAgentId ?? part.expertKey}#${part.attemptNo ?? 1}`;

/** 专家卡片是否已展开 */
const isExpertExpanded = (part: MessagePart) => expandedExpertKeys.value.includes(expertPartKey(part));

/** 展开 / 收起专家卡片 */
const toggleExpert = (part: MessagePart) => {
  const key = expertPartKey(part);
  expandedExpertKeys.value = isExpertExpanded(part)
    ? expandedExpertKeys.value.filter((item) => item !== key)
    : [...expandedExpertKeys.value, key];
};

/** 专家卡片状态文案 */
const expertStateLabel = (state?: string) => {
  if (state === 'success') {
    return '已回答';
  }
  if (state === 'invalid') {
    return '未完成';
  }
  return '调用中';
};

/** 专家卡片状态标签类型 */
const expertStateType = (state?: string): ElTagType => {
  if (state === 'success') {
    return 'success';
  }
  if (state === 'invalid') {
    return 'danger';
  }
  return 'info';
};

/**
 * 编排轨迹回填（REQ-AI-30）：把专家原始结论挂回对应卡片。
 *
 * 数据源为 ai_route_expert_call（raw_output 原文留证），按「专家主键 + 尝试次数」精确匹配本轮
 * 新建的专家卡片，因此卡片展示与事后追溯同源；回填失败不影响回答展示。
 */
const syncExpertConclusions = async (msg: LocalMessage) => {
  const sessionId = currentSessionId.value;
  if (!sessionId) {
    return;
  }
  try {
    const res = await listChatRouteRuns(sessionId);
    const run = (res.data ?? []).find((item) => String(item.messageId ?? '') === String(msg.id));
    if (!run?.expertCalls?.length) {
      return;
    }
    run.expertCalls.forEach((call) => {
      const target = msg.parts.find(
        (part) =>
          part.kind === 'expert' &&
          String(part.expertAgentId ?? '') === String(call.expertAgentId) &&
          (part.attemptNo ?? 1) === (call.attemptNo ?? 1)
      );
      if (target) {
        target.expertConclusion = call.parsedOutput || call.rawOutput || undefined;
      }
    });
  } catch {
    // 轨迹回填失败不影响回答展示
  }
};

/** 已手动展开的工具调用卡片（默认全部收起，点击卡片头部展开）；键为工具调用标识 */
const expandedToolKeys = ref<string[]>([]);

/** 工具调用卡片展开态的键 */
const toolPartKey = (part: MessagePart) => String(part.toolCallId ?? part.toolName ?? '');

/** 工具调用卡片是否已展开 */
const isToolExpanded = (part: MessagePart) => expandedToolKeys.value.includes(toolPartKey(part));

/** 切换工具调用卡片的展开 / 收起 */
const toggleTool = (part: MessagePart) => {
  const key = toolPartKey(part);
  expandedToolKeys.value = isToolExpanded(part)
    ? expandedToolKeys.value.filter((item) => item !== key)
    : [...expandedToolKeys.value, key];
};

/** 参考来源区块展开态的键（按消息主键；默认收起） */
const expandedSourceKeys = ref<string[]>([]);

/** 参考来源区块是否已展开 */
const isSourcesExpanded = (msg: LocalMessage) => expandedSourceKeys.value.includes(String(msg.id));

/** 切换参考来源区块的展开 / 收起 */
const toggleSources = (msg: LocalMessage) => {
  const key = String(msg.id);
  expandedSourceKeys.value = isSourcesExpanded(msg)
    ? expandedSourceKeys.value.filter((item) => item !== key)
    : [...expandedSourceKeys.value, key];
};

/** 工具状态标签颜色 */
const toolStateType = (state?: string): ElTagType => {
  if (state === 'success') {
    return 'success';
  }
  if (state === 'error') {
    return 'danger';
  }
  return 'info';
};

/** 工具状态文案 */
const toolStateLabel = (state?: string) => {
  if (state === 'success') {
    return '成功';
  }
  if (state === 'error') {
    return '失败';
  }
  return '调用中';
};

/** 确认卡片状态标签颜色 */
const confirmStateType = (state?: string): ElTagType => {
  if (state === 'approved') {
    return 'success';
  }
  if (state === 'rejected' || state === 'expired') {
    return 'danger';
  }
  return 'warning';
};

/** 确认卡片状态文案 */
const confirmStateLabel = (state?: string) => {
  if (state === 'approved') {
    return '已允许';
  }
  if (state === 'rejected') {
    return '已拒绝';
  }
  if (state === 'expired') {
    return '已过期';
  }
  return '待确认';
};

/** 格式化工具入参 */
const formatToolInput = (input?: Record<string, any>) => {
  if (!input || !Object.keys(input).length) {
    return '{}';
  }
  try {
    return JSON.stringify(input, null, 2);
  } catch {
    return String(input);
  }
};

/** 参考来源相似度标签样式：越高越绿（与检索页一致） */
const sourceScoreType = (score?: number): ElTagType => {
  if (score === undefined || score === null) {
    return 'info';
  }
  if (score >= 0.8) {
    return 'success';
  }
  return score >= 0.6 ? 'warning' : 'info';
};

/** 参考来源相似度格式化 */
const formatSourceScore = (score?: number) => (score === undefined || score === null ? '-' : score.toFixed(2));

/**
 * 参考来源抽屉状态。
 *
 * 来源只带「文档 + 切片序号」，点开时现查原文，避免把所有引用正文塞进消息记录。
 */
const sourceDrawer = reactive({
  visible: false,
  loading: false,
  docId: null as string | number | null,
  docName: '',
  meta: '',
  error: '',
  context: null as ChunkContextVO | null,
  fullText: '',
  showFullText: false
});

/** 来源是否可定位：历史来源可能缺少 docId */
const hasSourceDoc = (source: ChatSourceItem) => source.docId !== undefined && source.docId !== null;

/** 抽屉头部说明：章节路径 · 页码 · 相似度 */
const buildSourceMeta = (source: ChatSourceItem) => {
  const parts: string[] = [];
  if (source.headingPath) {
    parts.push('§ ' + source.headingPath);
  }
  if (source.pageNo !== undefined && source.pageNo !== null) {
    parts.push('第 ' + source.pageNo + ' 页');
  }
  if (source.score !== undefined && source.score !== null) {
    parts.push('相似度 ' + formatSourceScore(source.score));
  }
  return parts.join(' · ');
};

/** 加载并展示文档全文（历史来源缺切片序号、或用户想通读时兜底） */
const loadSourceFullText = async (docId: string | number) => {
  try {
    const res = await getDocumentContent(docId);
    sourceDrawer.fullText = res.data ?? '';
    sourceDrawer.showFullText = true;
    if (!sourceDrawer.fullText) {
      sourceDrawer.error = '文档尚未解析，暂无原文';
    }
  } catch {
    sourceDrawer.error = '无法读取文档全文（可能已删除或无权访问）';
  } finally {
    sourceDrawer.loading = false;
  }
};

/** 点击来源：按文档 + 切片序号现查命中原文；失败只降级提示，不打断对话 */
const handleOpenSource = async (source: ChatSourceItem) => {
  if (!hasSourceDoc(source)) {
    return;
  }
  const docId = source.docId as string | number;
  Object.assign(sourceDrawer, {
    visible: true,
    loading: true,
    docId,
    docName: source.docName || '参考来源',
    meta: buildSourceMeta(source),
    error: '',
    context: null,
    fullText: '',
    showFullText: false
  });
  if (source.chunkIndex === undefined || source.chunkIndex === null) {
    await loadSourceFullText(docId);
    return;
  }
  try {
    const res = await getDocumentChunkContext(docId, source.chunkIndex);
    sourceDrawer.context = res.data ?? null;
    if (!sourceDrawer.context?.target) {
      sourceDrawer.error = '来源文档已变更（可能已重新解析），当前序号无法定位';
    }
  } catch {
    sourceDrawer.error = '无法读取来源原文（文档可能已删除或无权访问）';
  } finally {
    sourceDrawer.loading = false;
  }
};

/** 从命中片段视图切到文档全文 */
const handleShowFullText = () => {
  if (sourceDrawer.docId === null) {
    return;
  }
  sourceDrawer.loading = true;
  loadSourceFullText(sourceDrawer.docId);
};

/**
 * 累积参考来源：按 docId + chunkIndex 去重（保留最高 score），与后端口径一致。
 */
const mergeMessageSources = (msg: LocalMessage, items?: ChatSourceItem[]) => {
  if (!items || !items.length) {
    return;
  }
  if (!msg.sources) {
    msg.sources = [];
  }
  items.forEach((item) => {
    const exist = msg.sources!.find(
      (s) => s.docId === item.docId && s.chunkIndex === item.chunkIndex
    );
    if (!exist) {
      msg.sources!.push(item);
    } else if (item.score !== undefined && item.score !== null && (exist.score === undefined || exist.score === null || item.score > exist.score)) {
      exist.score = item.score;
    }
  });
};

/**
 * 解析历史消息落库的参考来源（JSON 字符串）。
 */
const parseSources = (raw?: string): ChatSourceItem[] | undefined => {
  if (!raw) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length ? (parsed as ChatSourceItem[]) : undefined;
  } catch {
    return undefined;
  }
};

/** 滚动到底部 */
const scrollToBottom = () => {
  const el = messageContainerRef.value;
  if (el) {
    el.scrollTop = el.scrollHeight;
  }
};

const scrollToBottomSoon = () => {
  nextTick(scrollToBottom);
};

/** 关闭当前流并复位流式状态 */
const finishStream = () => {
  streaming.value = false;
  currentAssistantKey.value = undefined;
  sseData.value = null;
  closeStream();
};

/** 清掉某条消息的进度状态（收尾 / 失败 / 中止 / 待确认时调用） */
const clearPhase = (msg?: LocalMessage) => {
  if (!msg) {
    return;
  }
  msg.phaseText = undefined;
};

/** 工具调用状态 → 卡片状态（历史数据无 running，SUCCESS 视为成功，其余视为失败/未知） */
const toolPartState = (state?: string) => (state === 'SUCCESS' ? 'success' : 'error');

/** 编排协议内部工具（REQ-AI-30）：不渲染通用工具卡片，其决策与专家调用改由「路由提示条 + 专家卡片」呈现 */
const ROUTE_PROTOCOL_TOOLS = ['route_decision', 'submit_expert_result'];

/**
 * 按编排轨迹重建「路由提示条 + 专家卡片」（REQ-AI-30）。
 *
 * 与实时 SSE 的展示口径一致：提示条对应 route_decision 事件（仅当确有决策时才出现），
 * 专家卡片一对对应 expert_start 事件、结论取 ai_route_expert_call 的原文（parsed_output 优先，
 * 回落 raw_output），因此历史会话与实时会话的展示与追溯同源。
 */
const appendRouteParts = (msg: LocalMessage, run?: AiRouteRunDetailVO) => {
  if (!run) {
    return;
  }
  const calls = run.expertCalls ?? [];
  const routeParts: MessagePart[] = [];
  if (run.action === 'route' || run.action === 'pinned') {
    routeParts.push({
      kind: 'route',
      reason: run.action === 'pinned'
        ? `已指定专家：${calls[0]?.expertAgentName || '领域专家'}`
        : `小Z 判定：需咨询${calls[0]?.expertAgentName || '领域专家'}`
    });
  } else if (run.decisionJson) {
    routeParts.push({ kind: 'route', reason: '小Z 判定：本轮由自己回答' });
  }
  calls.forEach((call) => {
    routeParts.push({
      kind: 'expert',
      expertKey: call.expertAgentKey,
      expertAgentId: call.expertAgentId,
      expertName: call.expertAgentName,
      attemptNo: call.attemptNo,
      callKind: call.callKind,
      // pending_confirm＝专家命中「需确认」工具、暂停等用户处置：仍显示「调用中」，
      // 处置后由恢复段的 expert_result 回填；映射成 invalid 会误标「结构不合格」。
      // cancelled＝用户「停止生成」中断了这次专家调用：同样不映射成 invalid（不是专家的问题）
      expertState: call.status === 'success' ? 'success'
        : call.status === 'pending_confirm' || call.status === 'cancelled' ? 'running' : 'invalid',
      expertError: call.status === 'pending_confirm' || call.status === 'cancelled'
        ? undefined : call.validationError || undefined,
      expertConclusion: call.parsedOutput || call.rawOutput || undefined
    });
  });
  if (run.status === 'degraded') {
    routeParts.push({
      kind: 'route',
      reason: `已由小Z 自行回答：${run.degradeReason || '专家结论不可用'}`
    });
  }
  if (!routeParts.length) {
    return;
  }
  // 正文之前插入：与实时链路的顺序（决策 → 专家卡片 → 正文）一致
  const textIndex = msg.parts.findIndex((part) => part.kind === 'text');
  if (textIndex < 0) {
    msg.parts.push(...routeParts);
  } else {
    msg.parts.splice(textIndex, 0, ...routeParts);
  }
};

/** 历史消息 → 本地渲染结构（助手消息按「工具卡片在前、正文在后」重建，历史数据不存 parts 顺序，属已知近似） */
const toLocalMessage = (item: ChatMessageVO, tools: ToolCallLogVO[]): LocalMessage => {
  const parts: MessagePart[] = [];
  if (item.role === 'assistant') {
    tools
      .filter((tool) => !ROUTE_PROTOCOL_TOOLS.includes(tool.toolName ?? ''))
      .forEach((tool) => {
        parts.push({
          kind: 'tool',
          toolCallId: tool.toolCallId,
          toolName: tool.toolName,
          state: toolPartState(tool.state),
          input: tool.toolInput,
          output: tool.toolOutput,
          durationMs: tool.durationMs
        });
      });
  }
  if (item.content) {
    parts.push({ kind: 'text', text: item.content });
  }
  return {
    id: item.id,
    role: item.role,
    content: item.content ?? '',
    parts,
    streaming: false,
    status: item.status ?? '0',
    createTime: item.createTime,
    errorMsg: item.errorMsg,
    durationMs: item.durationMs,
    toolCallCount: item.toolCallCount,
    totalTokens: item.totalTokens,
    attachments: item.attachments ?? [],
    sources: parseSources(item.sources)
  };
};

/** 加载资源选项（专家 / 场景 / 工具 / 技能 / MCP 工具）：逐项降级，单个失败仅置空该项 */
const loadResourceOptions = async () => {
  const [experts, packs, tools, skills, mcp] = await Promise.allSettled([
    optionAgentExperts(),
    scenarioPackOptions(),
    optionAgentTool(),
    visibleSkillOptions(),
    scenarioPackMcpOptions()
  ]);
  expertOptions.value = experts.status === 'fulfilled' ? experts.value.data ?? [] : [];
  packOptions.value = packs.status === 'fulfilled' ? packs.value.data ?? [] : [];
  toolOptions.value = tools.status === 'fulfilled' ? tools.value.data ?? [] : [];
  skillOptions.value = skills.status === 'fulfilled' ? skills.value.data ?? [] : [];
  mcpOptions.value = mcp.status === 'fulfilled' ? mcp.value.data ?? [] : [];
};

/** 场景包选项的能力摘要 */
const packSummary = (item: ScenarioPackOptionVO) =>
  `工具 ${item.toolCount ?? 0} · MCP ${item.mcpToolCount ?? 0} · 技能 ${item.skillCount ?? 0}`;

/** 加载可选智能体（用于解析入口智能体「小Z」；任务工作台的会话恒归属它，REQ-AI-31） */
const loadAgents = async () => {
  const res = await optionAgent();
  agentOptions.value = res.data ?? [];
};

/** 入口智能体主键（会话发言人恒为入口智能体） */
const entryAgentId = computed(() => agentOptions.value.find((item) => item.role === 'entry')?.id);

/** 专家菜单文案：仅名称；关闭「自动路由」的专家追加「仅手动」（REQ-AI-33，「专家领域」已停用） */
const expertLabel = (item: ExpertOptionVO) =>
  `${item.agentName || String(item.agentId)}${item.autoRoute === '0' ? '（仅手动）' : ''}`;

/** 标签区：指定专家 + 会话资源集（专家在前，其余按选择顺序） */
const resourceChips = computed<ResourceChip[]>(() => {
  const chips: ResourceChip[] = [];
  if (pinnedExpert.value) {
    chips.push({
      key: `expert:${pinnedExpert.value.id}`,
      kind: 'expert',
      label: pinnedExpert.value.name,
      tip: '指定专家（在菜单中再次点击该专家可取消）'
    });
  }
  sessionResources.value.forEach((item) => {
    const kind = item.resourceType === 'mcp_tool' ? 'mcp' : item.resourceType;
    chips.push({
      key: `${item.resourceType}:${item.resourceId}:${item.resourceKey ?? ''}`,
      kind,
      label: item.displayName || item.resourceKey || String(item.resourceId),
      tip: item.subLabel,
      item: {
        resourceType: item.resourceType,
        resourceId: item.resourceId,
        resourceKey: item.resourceKey
      }
    });
  });
  return chips;
});

/**
 * 资源选择器的二级分组与三级菜单项。
 *
 * <p>三级菜单由模板渲染为**悬浮飞出面板**（非内联展开），面板内固定高度 + `overflow-y:auto`，
 * 故工具 / 技能等几十项时仍可上下滚动。</p>
 */
const resourceGroups = computed<ResourceMenuGroup[]>(() => [
  {
    key: 'expert',
    label: '专家（单选）',
    count: expertOptions.value.length,
    items: expertOptions.value.map((item) => ({
      key: `expert-${item.agentId}`,
      label: expertLabel(item),
      tip: item.capabilityDesc || item.description || '指定后本轮强制路由到该专家；再次点击可取消',
      selected: isExpertSelected(item.agentId),
      command: `expert:${item.agentId}`
    }))
  },
  {
    key: 'scenario',
    label: '场景',
    count: packOptions.value.length,
    items: packOptions.value.map((item) => ({
      key: `scenario-${item.id}`,
      label: item.packName,
      tip: `场景包 · ${packSummary(item)}${item.description ? ' · ' + item.description : ''}`,
      selected: isResourceSelected('scenario', item.id),
      command: `scenario:${item.id}`
    }))
  },
  {
    key: 'tool',
    label: '工具',
    count: toolOptions.value.length,
    items: toolOptions.value.map((item) => ({
      key: `tool-${item.toolCode}`,
      label: item.toolName,
      tip: `${item.toolType === '1' ? '写入（需确认）' : '只读'} · ${item.toolCode}${
        item.description ? ' · ' + item.description : ''
      }`,
      selected: isResourceSelected('tool', item.toolCode),
      command: `tool:${item.toolCode}`
    }))
  },
  {
    key: 'skill',
    label: '技能',
    count: skillOptions.value.length,
    items: skillOptions.value.map((item) => ({
      key: `skill-${item.id}`,
      label: item.name || String(item.id),
      tip: item.description || '技能',
      selected: isResourceSelected('skill', item.id),
      command: `skill:${item.id}`
    }))
  },
  {
    key: 'mcp',
    label: 'MCP 工具',
    count: mcpOptions.value.reduce((total, server) => total + (server.tools?.length ?? 0), 0),
    items: mcpOptions.value.flatMap((server) => [
      { key: `mcp-server-${server.mcpClientId}`, label: server.serverName, section: server.serverName },
      ...(server.tools || []).map((tool) => ({
        key: `mcp-${server.mcpClientId}-${tool}`,
        label: tool,
        tip: `MCP · ${server.serverName} · ${tool}（默认需确认）`,
        selected: isResourceSelected('mcp_tool', server.mcpClientId, tool),
        command: `mcp:${server.mcpClientId}:${tool}`
      }))
    ])
  }
]);

/** 专家是否已选中（单选） */
const isExpertSelected = (agentId: string | number) =>
  !!pinnedExpert.value && String(pinnedExpert.value.id) === String(agentId);

/**
 * 资源是否已选中。
 *
 * <p>业务工具按「工具编码」判定（菜单项以编码为标识）；MCP 工具按「连接 + 远端工具名」；
 * 场景 / 技能按主键。</p>
 */
const isResourceSelected = (type: ChatResourceType, token: string | number, key?: string) =>
  sessionResources.value.some((item) => matchesResource(item, type, String(token), key));

/** 资源项是否匹配（选中判定与移除定位共用） */
const matchesResource = (item: ChatResourceVO, type: ChatResourceType, token: string, key?: string) => {
  if (item.resourceType !== type) {
    return false;
  }
  if (type === 'tool') {
    return (item.resourceKey ?? '') === token;
  }
  if (type === 'mcp_tool') {
    return String(item.resourceId) === token && (item.resourceKey ?? '') === (key ?? '');
  }
  return String(item.resourceId) === token;
};

/** 菜单选择：按 index 前缀分派到对应分组 */
const handleResourceSelect = async (index: string) => {
  if (index.endsWith('__none__')) {
    return;
  }
  const parts = index.split(':');
  const group = parts[0];
  if (group === 'expert') {
    await toggleExpertResource(parts[1]);
  } else if (group === 'scenario') {
    await toggleResource('scenario', parts[1]);
  } else if (group === 'tool') {
    await toggleResource('tool', parts[1]);
  } else if (group === 'skill') {
    await toggleResource('skill', parts[1]);
  } else if (group === 'mcp') {
    await toggleResource('mcp_tool', parts[1], parts.slice(2).join(':'));
  }
};

/** 读取「尚未落库的指定专家」本地缓存（缺失或字段不完整时返回 undefined） */
const readPendingExpertCache = (): { id: string | number; name: string } | undefined => {
  const cached = cache.session.getJSON(PENDING_EXPERT_CACHE_KEY);
  if (!cached || cached.id === undefined || cached.id === null || !cached.name) {
    return undefined;
  }
  return { id: cached.id, name: cached.name };
};

/** 缓存「尚未落库的指定专家」：无会话时选择只在内存里，刷新即丢，故写本地兜住这一窗口 */
const savePendingExpertCache = (expert: { id: string | number; name: string }) => {
  cache.session.setJSON(PENDING_EXPERT_CACHE_KEY, expert);
};

/** 清除「尚未落库的指定专家」缓存：选择已随会话落库或用户取消后，避免脏数据长期留存 */
const clearPendingExpertCache = () => {
  cache.session.remove(PENDING_EXPERT_CACHE_KEY);
};

/** 刷新后恢复「尚未落库的指定专家」：仅当前无会话时生效，有会话时以服务端真值为准 */
const restorePendingExpertCache = () => {
  if (currentSessionId.value) {
    return;
  }
  const cached = readPendingExpertCache();
  if (cached) {
    pinnedExpert.value = cached;
  }
};

/** 指定 / 取消指定专家：已有会话即时提交，未创建会话暂存本地 */
const toggleExpertResource = async (agentId: string) => {
  if (isExpertSelected(agentId)) {
    await clearExpert();
    return;
  }
  const option = expertOptions.value.find((item) => String(item.agentId) === String(agentId));
  const name = option?.agentName || String(agentId);
  const label = option ? expertLabel(option) : name;
  if (currentSessionId.value) {
    await pinChatSessionExpert(currentSessionId.value, agentId);
    const session = sessions.value.find((item) => String(item.id) === String(currentSessionId.value));
    if (session) {
      session.pinnedExpertId = agentId;
      session.pinnedExpertName = name;
    }
    // 已随会话落库，本地兜底记录不再需要
    clearPendingExpertCache();
  } else {
    // 会话尚未创建：选择只在前端暂存，刷新会丢，持久化到本地兜住这个窗口
    savePendingExpertCache({ id: agentId, name: label });
  }
  pinnedExpert.value = { id: agentId, name: label };
};

/** 取消指定专家：清空 pinned_expert_id，回到自动路由；同一会话上下文不丢 */
const clearExpert = async () => {
  if (currentSessionId.value) {
    await pinChatSessionExpert(currentSessionId.value, null);
    const session = sessions.value.find((item) => String(item.id) === String(currentSessionId.value));
    if (session) {
      session.pinnedExpertId = null;
      session.pinnedExpertName = undefined;
    }
  }
  // 用户主动取消：一并清掉本地兜底记录（无论有无会话）
  clearPendingExpertCache();
  pinnedExpert.value = undefined;
};

/** 增 / 删一个资源项：已有会话即时提交，未创建会话暂存本地 */
const toggleResource = async (type: ChatResourceType, token: string, key?: string) => {
  if (isResourceSelected(type, token, key)) {
    const existing = sessionResources.value.find((item) => matchesResource(item, type, token, key));
    if (existing) {
      await removeResourceItem({
        resourceType: type,
        resourceId: existing.resourceId,
        resourceKey: existing.resourceKey
      });
    }
    return;
  }
  const item = buildResourceItem(type, token, key);
  if (!item) {
    return;
  }
  const payload: ChatResourceItem = {
    resourceType: type,
    resourceId: item.resourceId,
    resourceKey: item.resourceKey
  };
  if (currentSessionId.value) {
    await addChatResources(currentSessionId.value, [payload]);
  }
  sessionResources.value.push(item);
};

/** 由选项构造资源项（含展示名，供标签渲染） */
const buildResourceItem = (type: ChatResourceType, token: string, key?: string): ChatResourceVO | undefined => {
  if (type === 'scenario') {
    const pack = packOptions.value.find((item) => String(item.id) === String(token));
    if (!pack) {
      return undefined;
    }
    return {
      resourceType: 'scenario',
      resourceId: pack.id,
      resourceKey: pack.packCode,
      displayName: pack.packName,
      subLabel: `场景 · ${packSummary(pack)}`
    };
  }
  if (type === 'tool') {
    const tool = toolOptions.value.find((item) => item.toolCode === token);
    if (!tool || tool.id === undefined || tool.id === null) {
      return undefined;
    }
    return {
      resourceType: 'tool',
      resourceId: tool.id,
      resourceKey: tool.toolCode,
      displayName: tool.toolName,
      subLabel: tool.toolType === '1' ? '工具（写入，需确认）' : '工具（只读）',
      toolType: tool.toolType
    };
  }
  if (type === 'skill') {
    const skill = skillOptions.value.find((item) => String(item.id) === String(token));
    if (!skill) {
      return undefined;
    }
    return {
      resourceType: 'skill',
      resourceId: skill.id,
      resourceKey: skill.name,
      displayName: skill.name || String(skill.id),
      subLabel: '技能'
    };
  }
  const server = mcpOptions.value.find((item) => String(item.mcpClientId) === String(token));
  if (!server || !key) {
    return undefined;
  }
  return {
    resourceType: 'mcp_tool',
    resourceId: server.mcpClientId,
    resourceKey: key,
    displayName: key,
    subLabel: `MCP · ${server.serverName}`
  };
};

/** 移除一个资源项（叉号）：已有会话逻辑删除，未创建会话仅本地移除 */
const removeResourceItem = async (payload: ChatResourceItem) => {
  if (currentSessionId.value) {
    await removeChatResources(currentSessionId.value, [payload]);
  }
  sessionResources.value = sessionResources.value.filter(
    (item) =>
      !(
        item.resourceType === payload.resourceType &&
        String(item.resourceId) === String(payload.resourceId) &&
        (item.resourceKey ?? '') === (payload.resourceKey ?? '')
      )
  );
};

/** 标签叉号：专家走 pinned 接口，其余走资源集接口 */
const handleRemoveChip = async (chip: ResourceChip) => {
  if (chip.kind === 'expert') {
    await clearExpert();
    return;
  }
  if (chip.item) {
    await removeResourceItem(chip.item);
  }
};

/** 载入某个会话的资源集与指定专家（服务端真值） */
const loadSessionResourceSet = async (session: ChatSessionVO) => {
  // 载入已有会话即以其服务端真值为准，本地兜底的未落库选择作废
  clearPendingExpertCache();
  pinnedExpert.value = session.pinnedExpertId
    ? { id: session.pinnedExpertId, name: session.pinnedExpertName || String(session.pinnedExpertId) }
    : undefined;
  try {
    const res = await listChatResources(session.id);
    sessionResources.value = res.data ?? [];
  } catch {
    sessionResources.value = [];
  }
};

/** 加载我的会话列表（仅进行中的会话） */
const getSessions = async () => {
  const res = await listChatSession({ pageNum: 1, pageSize: 50, status: '0' });
  sessions.value = res.data?.rows ?? [];
};

/** 新建会话（清空当前会话上下文与资源选择，发送时再真正落库） */
const handleNewSession = () => {
  finishStream();
  currentSessionId.value = undefined;
  messages.value = [];
  expandedToolKeys.value = [];
  inputText.value = '';
  regenerateCheck.value = null;
  regenerateCheckMessageId.value = undefined;
  regenerateBackup = null;
  // 资源选择（REQ-AI-32）：新建会话时清空，需重新选择；选项列表保留
  sessionResources.value = [];
  // 一并清掉本地兜底的专家选择：避免刷新后又把已被清空的旧选择恢复出来
  clearPendingExpertCache();
  pinnedExpert.value = undefined;
};

/** 切换会话 */
const handleSelectSession = async (session: ChatSessionVO) => {
  if (session.id === currentSessionId.value) {
    return;
  }
  finishStream();
  currentSessionId.value = session.id;
  // 工具卡片展开态不跨会话保留
  expandedToolKeys.value = [];
  // 资源选择（REQ-AI-32）：切会话时载入该会话的服务端资源集与指定专家
  await loadSessionResourceSet(session);
  const [messageRes, toolRes, routeRes] = await Promise.all([
    listChatMessage(session.id),
    listChatToolCall(session.id),
    listChatRouteRuns(session.id)
  ]);
  const toolsByMessage = new Map<string, ToolCallLogVO[]>();
  (toolRes.data ?? []).forEach((tool) => {
    const key = String(tool.messageId);
    const list = toolsByMessage.get(key) ?? [];
    list.push(tool);
    toolsByMessage.set(key, list);
  });
  const runByMessage = new Map<string, AiRouteRunDetailVO>();
  (routeRes.data ?? []).forEach((run) => {
    if (run.messageId !== undefined && run.messageId !== null) {
      runByMessage.set(String(run.messageId), run);
    }
  });
  messages.value = (messageRes.data ?? []).map((item) => {
    const local = toLocalMessage(item, toolsByMessage.get(String(item.id)) ?? []);
    appendRouteParts(local, runByMessage.get(String(item.id)));
    return local;
  });
  // 恢复未决的工具确认卡片（Redis 暂存失效时标记为已过期）
  const pendingRes = await listChatPending(session.id);
  (pendingRes.data ?? []).forEach((pending) => {
    const target = messages.value.find((item) => String(item.id) === String(pending.messageId));
    if (!target) {
      return;
    }
    target.parts.push({
      kind: 'confirm',
      messageId: pending.messageId,
      toolCalls: pending.toolCalls ?? [],
      confirmState: pending.expired ? 'expired' : 'pending'
    });
  });
  // 最后一轮的重新生成可用性由后端预检决定（写操作轮次置灰）
  void refreshRegenerateCheck();
  scrollToBottomSoon();
};

/** 会话「更多操作」：置顶 / 重命名 / 删除 */
const handleMoreCommand = async (command: string, session: ChatSessionVO) => {
  if (command === 'top') {
    await handleToggleTop(session);
    return;
  }
  if (command === 'rename') {
    await handleRenameSession(session);
    return;
  }
  if (command === 'delete') {
    await handleDeleteSession(session);
  }
};

/** 置顶 / 取消置顶会话 */
const handleToggleTop = async (session: ChatSessionVO) => {
  const pinned = session.topFlag === '1';
  await topChatSession(session.id, !pinned);
  modal.msgSuccess(pinned ? '已取消置顶' : '已置顶');
  await getSessions();
};

/** 重命名会话 */
const handleRenameSession = async (session: ChatSessionVO) => {
  const result = await ElMessageBox.prompt('请输入新的任务标题', '重命名任务', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    closeOnClickModal: false,
    inputValue: session.sessionTitle || '',
    inputPlaceholder: '请输入任务标题',
    inputValidator: (value: string) => {
      if (!value || !value.trim()) {
        return '任务标题不能为空';
      }
      if (value.trim().length > 200) {
        return '任务标题不能超过 200 个字符';
      }
      return true;
    }
  }).catch(() => null);
  if (!result) {
    return;
  }
  await renameChatSession(session.id, result.value.trim());
  modal.msgSuccess('重命名成功');
  await getSessions();
};

/** 删除（终止）会话 */
const handleDeleteSession = async (session: ChatSessionVO) => {
  await modal.confirm(`是否确认删除任务「${session.sessionTitle || '未命名任务'}」？`);
  await closeChatSession(session.id);
  modal.msgSuccess('删除成功');
  if (session.id === currentSessionId.value) {
    finishStream();
    currentSessionId.value = undefined;
    messages.value = [];
  }
  await getSessions();
};

/** 消息时间显示：今天只显示时分，其余日期补上「月-日」 */
const formatMessageTime = (createTime?: string) => {
  if (!createTime) {
    return '';
  }
  // 后端返回形如 yyyy-MM-dd HH:mm:ss
  const full = createTime.slice(0, 16);
  const now = new Date();
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
  return full.startsWith(today) ? full.slice(11) : full.slice(5);
};

/** 复制消息文本 */
const { copy } = useClipboard();

const handleCopyMessage = async (msg: LocalMessage) => {
  await copy(msg.content ?? '');
  modal.msgSuccess('已复制');
};

/** 取该助手消息所属轮次的用户提问（消息列表按时间顺序，向前找最近一条用户消息） */
const roundUserMessageOf = (msg: LocalMessage) => {
  const index = messages.value.findIndex((item) => String(item.id) === String(msg.id));
  if (index < 0) {
    return undefined;
  }
  for (let i = index - 1; i >= 0; i -= 1) {
    if (messages.value[i].role === 'user') {
      return messages.value[i];
    }
  }
  return undefined;
};

/** 是否为会话最后一轮的助手消息（其后不再有用户提问） */
const isLastRoundAssistant = (msg: LocalMessage) => {
  if (msg.role !== 'assistant') {
    return false;
  }
  const index = messages.value.findIndex((item) => String(item.id) === String(msg.id));
  if (index < 0) {
    return false;
  }
  return !messages.value.slice(index + 1).some((item) => item.role === 'user');
};

/** 是否展示「重新生成」入口：仅最后一轮、非流式中、非待确认 */
const canRegenerate = (msg: LocalMessage) =>
  isLastRoundAssistant(msg)
  && !msg.streaming
  && msg.status !== '2'
  && roundUserMessageOf(msg) !== undefined;

/** 会话最后一轮的用户提问 */
const lastRoundUserMessage = computed(() =>
  [...messages.value].reverse().find((item) => item.role === 'user')
);

/** 只采用与当前最后一轮匹配的预检结果（防止切换会话后误用旧结果） */
const currentRegenerateCheck = computed(() => {
  const lastUser = lastRoundUserMessage.value;
  if (!lastUser || String(regenerateCheckMessageId.value) !== String(lastUser.id)) {
    return null;
  }
  return regenerateCheck.value;
});

/** 是否被预检明确拒绝（按钮置灰） */
const regenerateBlocked = computed(() => currentRegenerateCheck.value?.allowed === false);

/** 重新生成按钮悬停提示：写操作轮次展示后端给出的拒绝原因 */
const regenerateHint = computed(() => {
  const check = currentRegenerateCheck.value;
  return check && !check.allowed
    ? check.reason || '该轮不支持重新生成'
    : '重新生成该回答';
});

/** 刷新最后一轮的重新生成预检（切换会话 / 本轮结束后调用） */
const refreshRegenerateCheck = async () => {
  const sessionId = currentSessionId.value;
  const lastUser = lastRoundUserMessage.value;
  if (!sessionId || !lastUser || streaming.value) {
    regenerateCheck.value = null;
    regenerateCheckMessageId.value = undefined;
    return;
  }
  try {
    const res = await checkChatRegenerate(sessionId, lastUser.id);
    regenerateCheck.value = res.data ?? null;
    regenerateCheckMessageId.value = lastUser.id;
  } catch {
    // 预检失败不阻塞对话：按钮保持可用，点击时再校验并提示
    regenerateCheck.value = null;
    regenerateCheckMessageId.value = undefined;
  }
};

/** 后端在预检 / 上下文回退阶段拒绝时，恢复旧回答展示（此时新回答尚未产生） */
const restoreRegenerateBackup = () => {
  const backup = regenerateBackup;
  regenerateBackup = null;
  if (!backup) {
    return;
  }
  const placeholderId = currentAssistantKey.value;
  messages.value = messages.value.filter((item) => item.id !== placeholderId);
  messages.value.push(...backup);
  finishStream();
};

/** 重新生成最后一轮回答：先预检，再以 SSE 重跑（旧回答由后端逻辑删除保留审计行） */
const handleRegenerate = async (msg: LocalMessage) => {
  const sessionId = currentSessionId.value;
  const userMessage = roundUserMessageOf(msg);
  if (!sessionId || !userMessage || streaming.value || sending.value) {
    return;
  }
  try {
    const res = await checkChatRegenerate(sessionId, userMessage.id);
    const check = res.data;
    regenerateCheck.value = check ?? null;
    regenerateCheckMessageId.value = userMessage.id;
    if (!check?.allowed) {
      modal.msgWarning(check?.reason || '当前轮次不支持重新生成');
      return;
    }
    const confirmed = await modal.confirm('是否重新生成该回答？旧回答将被替换。').catch(() => null);
    if (!confirmed) {
      return;
    }
    const userIndex = messages.value.findIndex(
      (item) => String(item.id) === String(userMessage.id)
    );
    if (userIndex < 0) {
      return;
    }
    // 暂存旧回答：后端若在预检 / 上下文回退阶段拒绝，需要把它放回
    regenerateBackup = messages.value
      .slice(userIndex + 1)
      .map((item) => ({ ...item, parts: [...item.parts] }));
    messages.value.splice(userIndex + 1);
    const tempId = `pending-${Date.now()}`;
    messages.value.push({
      id: tempId,
      role: 'assistant',
      content: '',
      parts: [],
      streaming: true,
      status: '0'
    });
    currentAssistantKey.value = tempId;
    streaming.value = true;
    streamUrl.value = buildChatRegenerateUrl(sessionId, userMessage.id);
    openStream();
  } catch (error) {
    modal.msgError((error as Error)?.message || '重新生成失败');
  }
};

/** 删除一问一答（整轮）：该用户消息及其助手回复（后端仅删记录，不影响模型上下文） */
const handleDeleteMessage = async (msg: LocalMessage) => {
  const sessionId = currentSessionId.value;
  if (!sessionId) {
    return;
  }
  const confirmed = await modal.confirm('是否确认删除该提问及其回答？').catch(() => null);
  if (!confirmed) {
    return;
  }
  try {
    await deleteChatMessage(sessionId, msg.id);
  } catch (error) {
    // 写操作轮次会被后端拒绝删除（保留审计链路），直接展示后端原因
    modal.msgError((error as Error)?.message || '删除失败');
    return;
  }
  // 本地同步移除该轮：本条用户消息 + 紧随其后的助手消息（与后端口径一致）
  const start = messages.value.findIndex((item) => item.id === msg.id);
  if (start >= 0) {
    let end = start + 1;
    while (end < messages.value.length && messages.value[end].role !== 'user') {
      end++;
    }
    messages.value.splice(start, end - start);
  }
  modal.msgSuccess('删除成功');
  void refreshRegenerateCheck();
  await getSessions();
};

/** 停止生成：通知后端取消本轮执行（避免后端继续跑并落库），随后关闭前端流 */
const handleStop = () => {
  const sessionId = currentSessionId.value;
  if (sessionId) {
    // 通知后端取消；失败不阻塞前端收尾（如后端本轮已结束）
    stopChat(sessionId).catch(() => undefined);
  }
  const msg = currentAssistant();
  if (msg) {
    msg.streaming = false;
    clearPhase(msg);
    // 前端随即关闭 SSE，很可能收不到后端随后的 aborted 事件，故本地即时标记「已中止」
    msg.status = '3';
  }
  finishStream();
};

/** 处置待确认的工具调用：写入结果后建立恢复流继续生成 */
const handleConfirmTool = async (part: MessagePart, confirmed: boolean, remember: boolean, rejectReason?: string) => {
  // 只有待确认卡片可处置：挡住连点与竞态（已被置为 expired 的旧卡片不再重复提交）
  if (part.confirmState !== 'pending') {
    modal.msgWarning('该确认已失效，请重新提问');
    return;
  }
  const sessionId = currentSessionId.value;
  const messageId = part.messageId;
  if (!sessionId || messageId === undefined || messageId === null || streaming.value) {
    return;
  }
  // 拒绝原因仅拒绝时有意义：后端会回传模型并写入权限决策日志
  await confirmChatTool(sessionId, {
    messageId,
    confirmed,
    remember,
    rejectReason: confirmed ? undefined : rejectReason
  });
  part.confirmState = confirmed ? 'approved' : 'rejected';
  part.confirmReason = confirmed ? undefined : rejectReason;
  // 拒绝同样需要恢复流：由模型基于现有信息作答或说明无法完成，避免本轮悬空
  const target = messages.value.find((item) => item.id === messageId);
  if (!target) {
    return;
  }
  target.streaming = true;
  currentAssistantKey.value = messageId;
  streaming.value = true;
  streamUrl.value = buildChatResumeUrl(sessionId, messageId);
  openStream();
};

/** 拒绝待确认的工具调用：可填写拒绝原因（可选），交后端回传模型并留痕 */
const handleRejectTool = async (part: MessagePart) => {
  // 同样先校验状态：避免对已失效卡片弹出拒绝原因输入框
  if (part.confirmState !== 'pending') {
    modal.msgWarning('该确认已失效，请重新提问');
    return;
  }
  const result = await ElMessageBox.prompt('可填写拒绝原因（可选），将回传给模型并记入审计', '拒绝执行', {
    confirmButtonText: '确认拒绝',
    cancelButtonText: '取消',
    closeOnClickModal: false,
    inputPlaceholder: '如：数据敏感，暂不执行（可不填）',
    inputValidator: (value: string) => {
      if (value && value.trim().length > 200) {
        return '拒绝原因不能超过 200 个字符';
      }
      return true;
    }
  }).catch(() => null);
  if (!result) {
    return;
  }
  await handleConfirmTool(part, false, false, result.value?.trim() || undefined);
};

/** SSE 事件信封：补充后端新增的子智能体来源字段（主智能体自身发起时不带该字段） */
type ChatStreamEventWithSource = ChatStreamEvent & { source?: string };

/** 逐条解析 SSE 事件信封 */
const handleStreamEvent = (raw: string) => {
  let event: ChatStreamEventWithSource;
  try {
    event = JSON.parse(raw);
  } catch {
    return;
  }
  const msg = currentAssistant();
  if (!msg) {
    return;
  }

  switch (event.type) {
    case 'start':
      break;
    case 'phase': {
      // 对话期实时进度：长等待时播报后端在干什么（文案由后端拼好，前端只渲染）
      msg.phaseText = event.text ?? '';
      break;
    }
    case 'text_delta': {
      const delta = event.delta ?? '';
      // 正文开始流动即让状态条让位：只在「无可见输出」的静默期展示进度
      msg.phaseText = undefined;
      const last = msg.parts[msg.parts.length - 1];
      if (last && last.kind === 'text') {
        last.text = (last.text ?? '') + delta;
      } else {
        msg.parts.push({ kind: 'text', text: delta });
      }
      msg.content += delta;
      break;
    }
    case 'tool_call_start': {
      // 编排协议工具不出现通用工具卡片：其决策与专家调用由「路由提示条 + 专家卡片」呈现（见 appendRouteParts）
      if (ROUTE_PROTOCOL_TOOLS.includes(event.toolName ?? '')) {
        break;
      }
      msg.parts.push({
        kind: 'tool',
        toolCallId: event.toolCallId,
        toolName: event.toolName || '工具调用',
        source: event.source,
        state: 'running',
        input: ''
      });
      break;
    }
    case 'tool_call_delta': {
      const part = findToolPart(msg, event.toolCallId);
      if (part) {
        part.input = (part.input ?? '') + (event.delta ?? '');
      }
      break;
    }
    case 'tool_result': {
      const part = findToolPart(msg, event.toolCallId);
      if (part) {
        part.state = event.state === 'SUCCESS' ? 'success' : 'error';
        part.output = event.output ?? '';
        part.durationMs = event.durationMs;
        if (event.toolName) {
          part.toolName = event.toolName;
        }
        if (event.source) {
          part.source = event.source;
        }
      }
      break;
    }
    case 'tool_confirm': {
      // 工具被配置为「需人工确认」：本轮暂停，等待用户处置后再恢复
      msg.id = event.messageId ?? msg.id;
      msg.streaming = false;
      clearPhase(msg);
      // 后端待确认按 messageId 唯一暂存：同一助手消息内第二次暂停会覆盖旧槽位，
      // 若旧卡片仍留在前端且 messageId 与新卡片相同，用户点旧卡片时后端处理的却是新一批
      // 工具调用（点 A 却执行了 B）。故先把同消息内已有的 pending 卡片统一置为 expired。
      msg.parts.forEach((part) => {
        if (part.kind === 'confirm' && part.confirmState === 'pending') {
          part.confirmState = 'expired';
        }
      });
      msg.parts.push({
        kind: 'confirm',
        messageId: event.messageId,
        toolCalls: event.toolCalls ?? [],
        confirmState: 'pending'
      });
      finishStream();
      break;
    }
    case 'route_decision': {
      // 入口智能体的路由决策（REQ-AI-30/31）：先告知用户「小Z 怎么判的」
      const pinned = event.action === 'pinned';
      const isRoute = event.action === 'route' || pinned;
      msg.parts.push({
        kind: 'route',
        reason: pinned
          ? `已指定专家：${event.expertName || '领域专家'}`
          : isRoute
            ? `小Z 判定：需咨询${event.expertName || '领域专家'}`
            : '小Z 判定：本轮由自己回答'
      });
      break;
    }
    case 'expert_start': {
      // 每次专家调用（含重试与换专家）各出一张卡片，尝试次数如实展示
      msg.parts.push({
        kind: 'expert',
        expertKey: event.expertKey,
        expertAgentId: event.expertAgentId,
        expertName: event.expertName,
        attemptNo: event.attemptNo,
        callKind: event.callKind,
        expertState: 'running'
      });
      break;
    }
    case 'expert_result': {
      const part = findRunningExpertPart(msg, event.expertKey);
      if (part) {
        part.expertState = event.status === 'success' ? 'success' : 'invalid';
      }
      break;
    }
    case 'expert_invalid': {
      const part = findRunningExpertPart(msg, event.expertKey);
      if (part) {
        part.expertState = 'invalid';
        part.expertError = event.error;
      }
      break;
    }
    case 'route_fallback': {
      msg.parts.push({
        kind: 'route',
        reason: `已由小Z 自行回答：${event.reason || '专家结论不可用'}`
      });
      break;
    }
    case 'answer_reset': {
      // 指定专家兜底（REQ-AI-31）：模型整轮自答被服务端丢弃、改由「指定专家结论 + 小Z 整合」作答，
      // 故清掉已渲染的文本片段（保留工具 / 路由卡片，其后会追加强制编排的路由与专家卡片）
      msg.parts = msg.parts.filter((part) => part.kind !== 'text');
      msg.content = '';
      break;
    }
    case 'references': {
      // 回答用到知识库：累积参考来源（去重后展示在助手气泡下方）
      mergeMessageSources(msg, event.items);
      break;
    }
    case 'done': {
      msg.id = event.messageId ?? msg.id;
      msg.streaming = false;
      clearPhase(msg);
      msg.durationMs = event.durationMs;
      msg.toolCallCount = event.toolCallCount;
      msg.totalTokens = event.totalTokens;
      // done 载荷与 references 事件同结构：兜底回填（防 references 事件丢失）
      mergeMessageSources(msg, event.references);
      finishStream();
      // 重新生成成功：旧回答快照已无意义，刷新预检（新一轮回答可能含写操作）
      regenerateBackup = null;
      void refreshRegenerateCheck();
      getSessions();
      // 编排轨迹回填：异步补齐专家原始结论，不阻塞本轮收尾
      void syncExpertConclusions(msg);
      break;
    }
    case 'aborted': {
      // 中止也按收尾处理：给用户明确的「已中止」反馈（兜底服务端主动中止等场景）
      msg.id = event.messageId ?? msg.id;
      msg.streaming = false;
      clearPhase(msg);
      msg.durationMs = event.durationMs ?? msg.durationMs;
      msg.status = '3';
      finishStream();
      regenerateBackup = null;
      void refreshRegenerateCheck();
      break;
    }
    case 'error': {
      if (event.reasonCode && regenerateBackup) {
        // 预检 / 上下文回退被拒：本轮尚未产生新回答，恢复旧回答展示
        restoreRegenerateBackup();
        modal.msgError(event.message || '重新生成被拒绝');
        break;
      }
      msg.streaming = false;
      clearPhase(msg);
      msg.status = '1';
      msg.errorMsg = event.message || '对话失败';
      modal.msgError(msg.errorMsg);
      finishStream();
      regenerateBackup = null;
      break;
    }
  }
  scrollToBottomSoon();
};

/** 确保会话存在（无则创建并提交本地暂存资源），返回会话主键 */
const ensureSession = async (): Promise<string | number | undefined> => {
  if (currentSessionId.value) {
    return currentSessionId.value;
  }
  const agentId = entryAgentId.value;
  if (agentId === undefined || agentId === '') {
    modal.msgWarning('未找到入口智能体「小Z」，请先在「小Z 配置」中启用它');
    return undefined;
  }
  // 场景包参数停写（REQ-AI-32：改用会话级资源集），仅保留指定专家
  const sessionRes = await createChatSession(agentId, undefined, undefined, pinnedExpert.value?.id);
  const session = sessionRes.data;
  if (!session?.id) {
    modal.msgError('创建任务失败');
    return undefined;
  }
  currentSessionId.value = session.id;
  // 指定专家已随 pinnedExpertId 落库，本地兜底记录可清除
  clearPendingExpertCache();
  // 首次建会话时把本地暂存的资源选择一并提交（REQ-AI-32）
  if (sessionResources.value.length) {
    await addChatResources(
      session.id,
      sessionResources.value.map((item) => ({
        resourceType: item.resourceType,
        resourceId: item.resourceId,
        resourceKey: item.resourceKey
      }))
    );
  }
  await getSessions();
  return session.id;
};

/** 点击「上传附件」菜单项：打开文件选择框（流式回答中禁用） */
const handleTriggerAttachment = () => {
  if (streaming.value || uploadingAttachment.value) {
    return;
  }
  attachmentInputRef.value?.click();
};

/** 选择文件后：先经框架 OSS 上传，再按 ossId 登记（校验类型/大小并解析文档） */
const handleAttachmentChange = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) {
    return;
  }
  if (pendingAttachments.value.length >= MAX_ATTACHMENTS) {
    modal.msgWarning(`单条消息最多上传 ${MAX_ATTACHMENTS} 个附件`);
    return;
  }
  uploadingAttachment.value = true;
  try {
    const sessionId = await ensureSession();
    if (sessionId === undefined) {
      return;
    }
    const form = new FormData();
    form.append('file', file);
    const uploadRes = await uploadOss(form);
    const ossId = uploadRes.data?.ossId;
    if (ossId === undefined || ossId === null || ossId === '') {
      modal.msgError('附件上传失败');
      return;
    }
    const res = await registerChatAttachment(sessionId, { ossId, fileName: file.name });
    if (res.data?.id === undefined || res.data?.id === null) {
      modal.msgError('附件登记失败');
      return;
    }
    pendingAttachments.value.push(res.data);
  } catch (error) {
    modal.msgError((error as Error)?.message || '附件上传失败');
  } finally {
    uploadingAttachment.value = false;
  }
};

/** 移除待发附件（同步删除服务端暂存件） */
const handleRemoveAttachment = async (item: ChatAttachmentVO) => {
  pendingAttachments.value = pendingAttachments.value.filter((att) => att.id !== item.id);
  const sessionId = currentSessionId.value;
  if (sessionId !== undefined && sessionId !== null) {
    try {
      await deleteChatAttachment(sessionId, item.id);
    } catch {
      // 暂存件删除失败可忽略，不影响发送
    }
  }
};

/**
 * 取附件的新签访问地址。
 *
 * <p>历史回显的地址是**预签名 URL**（默认 120 秒有效）：页面停留超时后再打开会 403
 * （MinIO `Request has expired`），表现为缩略图还在、点开原图空白。故打开前先换一个新签地址；
 * 换签失败时回退到原地址（至少还有机会命中浏览器缓存）。</p>
 */
const resolveFreshAttachmentUrl = async (item: ChatAttachmentVO): Promise<string | undefined> => {
  const sessionId = currentSessionId.value;
  if (!sessionId || item.id === undefined || item.id === null) {
    return item.url;
  }
  try {
    const res = await refreshChatAttachmentUrl(sessionId, item.id);
    return res.data?.url || item.url;
  } catch {
    return item.url;
  }
};

/** 图片原图预览开关与地址 */
const previewVisible = ref(false);
const previewUrl = ref('');

/** 点击图片附件：换新签地址后打开原图预览 */
const handlePreviewAttachment = async (item: ChatAttachmentVO) => {
  const url = await resolveFreshAttachmentUrl(item);
  if (!url) {
    return;
  }
  previewUrl.value = url;
  previewVisible.value = true;
};

/** 点击文档附件：换新签地址后新窗口打开（同样避免预签名过期） */
const handleOpenAttachment = async (item: ChatAttachmentVO) => {
  const url = await resolveFreshAttachmentUrl(item);
  if (url) {
    window.open(url, '_blank', 'noopener');
  }
};

/**
 * 输入框快捷键：Enter / Ctrl+Enter 发送，Shift+Enter 换行。
 *
 * 必须挡掉输入法组合态：中文候选框里按 Enter 是「选词」，不加这个判断会把选词误当发送。
 */
const handleComposerKeydown = (event: KeyboardEvent) => {
  if (event.isComposing || event.keyCode === 229) {
    return;
  }
  if (event.key !== 'Enter' || event.shiftKey) {
    return;
  }
  event.preventDefault();
  void handleSend();
};

/**
 * 发送一轮对话：落库用户消息 → 追加助手占位 → 建立 SSE 流。
 * handleSend 与「失败重试」共用同一路径，保证重试与正常发送行为完全一致。
 * 返回是否成功启动本轮，供调用方决定是否清空输入区。
 */
const startChatRound = async (
  content: string,
  attachmentIds: Array<string | number>,
  retrySource?: LocalMessage
): Promise<boolean> => {
  if (streaming.value || sending.value) {
    return false;
  }
  sending.value = true;
  try {
    const sessionId = await ensureSession();
    if (sessionId === undefined) {
      return false;
    }

    const res = await sendChatMessage(sessionId, content, attachmentIds);
    const userMessageId = res.data?.id;
    if (userMessageId === undefined || userMessageId === null) {
      modal.msgError('消息发送失败');
      return false;
    }

    // 重试：先摘掉上一轮失败的错误气泡与其用户气泡，避免历史里出现重复消息
    if (retrySource) {
      const failedUserId = retrySource.retryPayload?.userMessageId;
      messages.value = messages.value.filter(
        (item) =>
          item !== retrySource &&
          (failedUserId === undefined || failedUserId === null || String(item.id) !== String(failedUserId))
      );
    }

    messages.value.push({
      id: userMessageId,
      role: 'user',
      content,
      parts: content ? [{ kind: 'text', text: content }] : [],
      streaming: false,
      status: '0',
      createTime: res.data?.createTime,
      attachments: res.data?.attachments ?? []
    });
    const tempId = `pending-${Date.now()}`;
    const assistant: LocalMessage = {
      id: tempId,
      role: 'assistant',
      content: '',
      parts: [],
      streaming: true,
      status: '0',
      // 记录本轮正文/附件/用户消息主键：失败时据此原样重发（见 handleRetryMessage）
      retryPayload: { content, userMessageId, attachmentIds }
    };
    messages.value.push(assistant);
    currentAssistantKey.value = tempId;
    scrollToBottomSoon();

    streaming.value = true;
    streamUrl.value = buildChatStreamUrl(sessionId, userMessageId);
    openStream();
    return true;
  } finally {
    sending.value = false;
  }
};

/** 发送消息：先落库拿 messageId，再建立 SSE 流 */
const handleSend = async () => {
  const content = inputText.value.trim();
  const attachmentIds = pendingAttachments.value.map((att) => att.id);
  if ((!content && !attachmentIds.length) || streaming.value || sending.value) {
    return;
  }
  const started = await startChatRound(content, attachmentIds);
  if (!started) {
    return;
  }
  // 发送成功后才清空输入区与待发附件（失败时保留文案，便于用户直接重发）
  inputText.value = '';
  pendingAttachments.value = [];
};

/** 失败重试：用该轮记录的正文与附件重发（不经过输入框，避免覆盖用户当前草稿） */
const handleRetryMessage = async (msg: LocalMessage) => {
  const payload = msg.retryPayload;
  if (!payload) {
    return;
  }
  await startChatRound(payload.content, payload.attachmentIds, msg);
};

watch(sseData, (raw) => {
  if (raw) {
    handleStreamEvent(String(raw));
  }
});

watch(sseError, (err) => {
  if (!err || !streaming.value) {
    return;
  }
  const msg = currentAssistant();
  if (msg) {
    msg.streaming = false;
    clearPhase(msg);
    msg.status = '1';
    msg.errorMsg = '连接中断，请重试';
  }
  modal.msgError('连接中断，请重试');
  finishStream();
  regenerateBackup = null;
});

onMounted(() => {
  // 刷新后恢复「尚未落库的指定专家」（仅当前无会话时生效），兜住选择随刷新丢失的窗口
  restorePendingExpertCache();
  loadAgents();
  loadResourceOptions();
  getSessions();
});
</script>

<style scoped lang="scss">
.chat-workbench {
  display: flex;
  /* 覆盖全局 .p-2 的纵向排列，保持左右结构（窄屏在下方媒体查询中改回纵向） */
  flex-direction: row;
  gap: 12px;
  /* 首帧兜底（默认「固定头部 + 标签页」：顶部占位 111px + 底部内边距 12px）；
     挂载后由脚本按父容器实测覆盖，避免撑高文档出现页面级滚动条 */
  height: calc(100vh - 123px);
}

/* 左侧：会话列表 */
.chat-sidebar {
  display: flex;
  flex-direction: column;
  flex: 0 0 280px;
  width: 280px;
  border: 1px solid var(--app-surface-border);
  border-radius: var(--app-radius-base, 12px);
  background: var(--app-surface-bg);
  box-shadow: var(--app-shadow-sm);
  overflow: hidden;
}

.sidebar-top {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-bottom: 1px solid var(--app-surface-border);
}

.new-session-btn {
  width: 100%;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 6px;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: background-color 0.2s ease;

  &:hover {
    background: var(--app-elevated-soft-bg);
  }

  &.is-active {
    border-color: var(--app-accent-strong);
    background: var(--app-accent-soft);
  }
}

.session-item__main {
  flex: 1;
  min-width: 0;
}

.session-item__title {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--app-text-title);
  font-size: 14px;
  font-weight: 600;
}

.session-item__pin {
  flex: 0 0 auto;
  color: var(--app-accent-strong);
  font-size: 13px;
}

.session-item__text {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-item__time {
  margin-top: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.session-item__more {
  flex: 0 0 auto;
  color: var(--app-text-muted);
  cursor: pointer;

  &:hover {
    color: var(--app-accent-strong);
  }
}

/* 右侧：会话内容 */
.chat-main {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  border: 1px solid var(--app-surface-border);
  border-radius: var(--app-radius-base, 12px);
  background: var(--app-surface-bg);
  box-shadow: var(--app-shadow-sm);
  overflow: hidden;
}

.message-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

/* 对话前（无消息）：隐藏历史区，让输入区浮到聊天区域中间；不再显示空态文案与插图 */
.message-area--empty {
  display: none;
}

/* 对话前：整体（标题 + 输入框 + +/发送操作行）水平居中，内容宽最大 800px
   （832 = 800 + 左右内边距 16×2）；对话后该类移除，恢复充满区域 */
.composer--center {
  align-self: center;
  width: 100%;
  max-width: 832px;
  margin-top: auto;
  margin-bottom: auto;
}

/* 对话前标题：位于输入框上方约两行正文高度处，水平居中（对话后不渲染） */
.composer__title {
  margin-bottom: 44px;
  color: var(--el-text-color-primary, #303133);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-align: center;
}

.msg-row {
  display: flex;
  margin-bottom: 18px;
}

.msg-row--user {
  justify-content: flex-end;
}

/* 用户消息块：气泡 + 悬停操作行（操作行绝对定位于气泡下方，落在行间距里，常态不占位也不引起布局跳动） */
.msg-block {
  position: relative;
  display: flex;
  flex-direction: column;
  max-width: 78%;
}

.msg-block--user {
  align-items: flex-end;
}

.msg-block--user > .bubble {
  max-width: 100%;
}

/* 用户消息附件：图片缩略图 / 文档卡片（位于气泡上方） */
.msg-attachments {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 100%;
  margin-bottom: 6px;
}

.msg-attachment-image-wrap {
  display: inline-block;
  line-height: 0;
  border-radius: 10px;
  cursor: zoom-in;
}

.msg-attachment-image-wrap:focus-visible {
  outline: 2px solid var(--app-accent-strong);
  outline-offset: 2px;
}

.msg-attachment-image {
  width: 120px;
  height: 120px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--app-surface-border, #e4e7ed);
  cursor: zoom-in;
}

.msg-attachment-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 6px 10px;
  border-radius: 10px;
  border: 1px solid var(--app-surface-border, #e4e7ed);
  background: var(--app-elevated-soft-bg, #ffffff);
  font-size: 12px;
  color: var(--app-text-title, #303133);
  text-decoration: none;
}

.msg-attachment-file__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-actions {
  position: absolute;
  top: 100%;
  right: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 18px;
  font-size: 12px;
  line-height: 1;
  color: var(--app-text-muted);
  visibility: hidden;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.msg-block--user:hover .msg-actions,
.msg-block--user:focus-within .msg-actions {
  visibility: visible;
  opacity: 1;
}

.msg-actions__icon {
  font-size: 13px;
  cursor: pointer;

  &:hover {
    color: var(--app-accent-strong);
  }
}

.msg-row--assistant {
  justify-content: flex-start;
  align-items: flex-start;
  gap: 10px;
}

.msg-row--system {
  justify-content: center;
  color: var(--app-text-muted);
  font-size: 13px;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  overflow: hidden;
}

.avatar__logo {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.75;
}

.bubble--user {
  background: var(--app-accent-strong);
  color: #ffffff;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble--assistant {
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
  color: var(--app-text-title);
}

.typing-cursor {
  display: inline-block;
  width: 8px;
  height: 16px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--app-accent-strong);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

/* 对话期实时进度条：与光标二选一展示（正文开流即让位），故不复用光标位置 */
.phase-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  color: var(--app-text-muted);
  font-size: 12px;
  animation: phase-fade-in 120ms ease-out;
}

.phase-bar__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-accent-strong);
  animation: phase-pulse 1.2s ease-in-out infinite;
}

.phase-bar__text {
  word-break: break-word;
}

@keyframes phase-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes phase-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  min-height: 18px;
  color: var(--app-text-muted);
  font-size: 12px;
}

/* 中止标记：与元信息同级，沿用次要文字色，视觉克制 */
.msg-meta__aborted {
  color: var(--app-text-muted);
  font-size: 12px;
  opacity: 0.75;
}

/* 复制图标：始终显示（左对齐） */
.msg-meta__icon {
  cursor: pointer;
  font-size: 14px;
  transition: color 0.2s;
}

.msg-meta__icon:hover {
  color: var(--el-color-primary, #409eff);
}

/* 重新生成：与元信息同级的小号文字按钮，不额外撑高操作区 */
.msg-meta__regenerate {
  height: 18px;
  padding: 0;
  font-size: 12px;
}

/* 文字区域：悬停回复内容才显示；整体前带一个分隔竖线（文字项之间不加） */
.msg-meta__info {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  opacity: 0;
  transition: opacity 0.2s;
}

.msg-meta__info::before {
  content: '|';
  opacity: 0.5;
}

.bubble--assistant:hover .msg-meta__info,
.msg-meta__info:focus-within {
  opacity: 1;
}

/* 「由 AI 生成」声明：始终显示、右对齐，弱化处理 */
.msg-meta__ai {
  margin-left: auto;
  opacity: 0.75;
}

/* 参考来源：助手气泡下方的出处区块（默认收起，点击标题展开 / 收起） */
.msg-sources {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--app-surface-border);
}

.msg-sources__head {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
  outline: none;
}

.msg-sources__head:hover .msg-sources__title {
  color: var(--el-color-primary);
}

.msg-sources__arrow {
  font-size: 12px;
  color: var(--app-text-muted);
  transition: transform 0.2s ease;
}

.msg-sources__arrow.is-expanded {
  transform: rotate(90deg);
}

.msg-sources__title {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.msg-sources__body {
  margin-top: 6px;
}

.msg-sources__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
  color: var(--app-text-muted);
}

/* 可点击来源：给出明确的悬停与键盘焦点反馈（点击后打开命中原文抽屉） */
.msg-sources__item.is-clickable {
  padding: 3px 6px;
  margin: 0 -6px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 120ms ease;
}

.msg-sources__item.is-clickable:hover,
.msg-sources__item.is-clickable:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}

/* 参考来源抽屉：命中片段强调整块，前后邻居弱化，便于一眼看出引用位置 */
.source-detail {
  min-height: 120px;
  padding-bottom: 8px;
}

.source-detail__meta {
  margin-bottom: 12px;
  color: var(--app-text-muted);
  font-size: 12px;
  word-break: break-word;
}

.source-detail__chunk {
  padding: 10px 12px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.source-detail__chunk--neighbor {
  opacity: 0.72;
}

.source-detail__chunk--target {
  border: 1px solid var(--app-accent-strong);
  background: var(--el-color-primary-light-9);
}

.source-detail__label {
  margin-bottom: 6px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.source-detail__text,
.source-detail__fulltext {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  font-size: 13px;
}

.source-detail__toolbar {
  margin-top: 4px;
  text-align: right;
}

.msg-sources__text {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.msg-sources__icon {
  font-size: 13px;
}

.msg-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--el-color-danger, #f56c6c);
  font-size: 12px;
}

/* 工具调用卡片 */
.tool-card {
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--app-surface-border);
  border-radius: 10px;
  background: var(--app-surface-bg);
}

.tool-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.tool-card__arrow {
  color: var(--app-text-muted);
  transition: transform 0.2s ease;
}

.tool-card__arrow.is-expanded {
  transform: rotate(90deg);
}

.tool-card__icon {
  color: var(--app-accent-strong);
}

.tool-card__name {
  color: var(--app-text-title);
  font-weight: 600;
  font-size: 13px;
}

.tool-card__meta {
  margin-left: auto;
  color: var(--app-text-muted);
  font-size: 12px;
}

.tool-card__body {
  margin-top: 8px;
}

.tool-card__section + .tool-card__section {
  margin-top: 8px;
}

.tool-card__label {
  margin-bottom: 4px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.tool-code {
  max-height: 220px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: 8px;
  background: var(--app-elevated-soft-bg);
  color: var(--app-text-title);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

/* 编排提示（REQ-AI-30）：小Z 的路由决策与降级说明 */
.route-note {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 6px 0;
  padding: 6px 10px;
  border-left: 3px solid var(--el-color-primary);
  border-radius: 6px;
  background: var(--app-elevated-soft-bg);
  color: var(--app-text-muted);
  font-size: 12px;
}

.route-note__icon {
  color: var(--el-color-primary);
}

/* 专家来源卡片（REQ-AI-30）：可展开查看专家原始结论（原文留证） */
.expert-card {
  margin: 6px 0;
  padding: 8px 10px;
  border: 1px solid var(--app-border-color, var(--el-border-color-lighter));
  border-radius: 8px;
  background: var(--app-elevated-bg, var(--el-bg-color));
}

.expert-card__head {
  display: flex;
  gap: 6px;
  align-items: center;
  cursor: pointer;
}

.expert-card__arrow {
  color: var(--app-text-muted);
  transition: transform 0.2s ease;
}

.expert-card__arrow.is-expanded {
  transform: rotate(90deg);
}

.expert-card__icon {
  color: var(--el-color-primary);
}

.expert-card__name {
  color: var(--app-text-title);
  font-size: 13px;
  font-weight: 600;
}

.expert-card__error {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.expert-card__body {
  margin-top: 8px;
}

/* 人工确认卡片（HITL） */
.confirm-card {
  margin: 10px 0;
  padding: 10px 12px;
  border: 1px solid var(--app-surface-border);
  border-left: 3px solid var(--el-color-warning);
  border-radius: 10px;
  background: var(--app-surface-bg);
}

.confirm-card--approved {
  border-left-color: var(--el-color-success);
}

.confirm-card--rejected,
.confirm-card--expired {
  border-left-color: var(--el-color-danger);
  opacity: 0.75;
}

.confirm-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.confirm-card__icon {
  color: var(--el-color-warning);
}

.confirm-card__title {
  color: var(--app-text-title);
  font-weight: 600;
  font-size: 13px;
}

.confirm-card__item {
  margin-top: 8px;
}

.confirm-card__tool {
  margin-bottom: 4px;
  color: var(--app-text-title);
  font-size: 12px;
  font-weight: 600;
}

/* 工具说明：次要色小号字，克制呈现，不喧宾夺主 */
.confirm-card__desc {
  margin-bottom: 6px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.confirm-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.confirm-card__tip {
  margin-top: 8px;
  color: var(--app-text-muted);
  font-size: 12px;
}

/* 底栏（无上分隔线：与历史对话区融为一体，不分隔） */
.composer {
  padding: 12px 16px;
}

.composer-actions {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 10px;
}

/* 资源选择器（REQ-AI-32）：最左侧 +，选中项以标签跟在其后 */
.resource-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.resource-add-btn {
  flex: none;
}

.resource-menu {
  padding: 2px 0;
}

/* 顶部动作项（上传附件）：样式与分组行一致，保持菜单观感统一 */
.resource-menu__action {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  cursor: pointer;
}

.resource-menu__action:hover {
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-color-primary);
}

.resource-menu__action-icon {
  flex: none;
  font-size: 14px;
}

.resource-menu__action-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 分割线：把顶部动作项与资源分组隔开 */
.resource-menu__divider {
  height: 1px;
  margin: 4px 6px;
  background: var(--el-border-color-lighter, #ebeef5);
}

/* 二级：分组行（悬停 / 点击即展开三级飞出菜单） */
.resource-menu__group {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  cursor: pointer;
}

.resource-menu__group.is-active {
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-color-primary);
}

.resource-menu__group-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-menu__group-count {
  flex: none;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.resource-menu__arrow {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}

/* 三级：悬浮飞出面板（**向上**生长 + 上下滚动）。
   选择器位于屏幕底部（弹层在 `+` 上方），若向下生长，靠下的分组（工具 / 技能 / MCP）面板会被视口底部截断，
   故面板底边与分组行对齐、向上展开；高度受视口限制，超长则面板内滚动。 */
.resource-flyout {
  position: absolute;
  left: 100%;
  bottom: -6px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  width: 260px;
  max-height: min(60vh, 420px);
  margin-left: 4px;
  padding: 6px 0;
  background: var(--el-bg-color-overlay, #fff);
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 6px;
  box-shadow: var(--el-box-shadow-light);
}

.resource-flyout__title {
  flex: none;
  padding: 4px 12px 6px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.resource-flyout__scroll {
  flex: 1;
  min-height: 0;
  max-height: min(320px, 50vh);
  overflow-y: auto;
  overscroll-behavior: contain;
}

.resource-flyout__scroll::-webkit-scrollbar {
  width: 6px;
}

.resource-flyout__scroll::-webkit-scrollbar-thumb {
  background: var(--el-border-color, #dcdfe6);
  border-radius: 3px;
}

.resource-flyout__empty {
  padding: 8px 12px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.resource-flyout__section {
  padding: 6px 12px 2px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}

.resource-flyout__item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
  cursor: pointer;
}

.resource-flyout__item:hover {
  background: var(--el-fill-color-light, #f4f4f5);
}

.resource-flyout__item.is-selected {
  color: var(--el-color-primary);
}

.resource-flyout__item-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-menu__check {
  flex: none;
  color: var(--el-color-primary);
}

.resource-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.resource-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  max-width: 220px;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 20px;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-regular, #606266);
  border: 1px solid var(--app-surface-border, #e4e7ed);
}

.resource-chip--expert {
  background: var(--el-color-warning-light-9, #fdf6ec);
  border-color: var(--el-color-warning-light-5, #f5dab1);
  color: var(--el-color-warning-dark-2, #b88230);
}

.resource-chip__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 悬停或键盘聚焦时，在标签左侧显示叉号（点击即移除，可随时从菜单加回） */
.resource-chip__close {
  flex: none;
  width: 12px;
  height: 12px;
  margin-right: 2px;
  font-size: 12px;
  cursor: pointer;
  visibility: hidden;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.resource-chip:hover .resource-chip__close,
.resource-chip:focus-within .resource-chip__close {
  visibility: visible;
  opacity: 1;
}

.resource-chip__close:hover {
  color: var(--el-color-danger, #f56c6c);
}

/* 聊天附件：待发附件标签（上传入口在 + 菜单顶部） */
.attachment-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.attachment-input {
  display: none;
}

.attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  max-width: 220px;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 20px;
  background: var(--el-fill-color-light, #f4f4f5);
  color: var(--el-text-color-regular, #606266);
  border: 1px solid var(--app-surface-border, #e4e7ed);
}

.attachment-chip--image {
  background: var(--el-color-primary-light-9, #ecf5ff);
  border-color: var(--el-color-primary-light-7, #c6e2ff);
}

.attachment-chip__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attachment-chip__close {
  flex: none;
  width: 12px;
  height: 12px;
  margin-right: 2px;
  font-size: 12px;
  cursor: pointer;
  visibility: hidden;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.attachment-chip:hover .attachment-chip__close,
.attachment-chip:focus-within .attachment-chip__close {
  visibility: visible;
  opacity: 1;
}

.attachment-chip__close:hover {
  color: var(--el-color-danger, #f56c6c);
}

/* 提示与发送按钮靠右；资源选择器保持在同一行左侧 */
.composer-actions-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.composer-tip {
  color: var(--app-text-muted);
  font-size: 12px;
}

@media (max-width: 900px) {
  .chat-workbench {
    flex-direction: column;
    height: auto;
  }

  .chat-sidebar {
    width: 100%;
    flex: 0 0 auto;
    max-height: 260px;
  }

  .chat-main {
    min-height: 520px;
  }
}
</style>
