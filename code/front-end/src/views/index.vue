<template>
  <div class="home">
    <section class="hero-panel">
      <div class="hero-copy">
        <span class="hero-badge">企业级智能业务运行平台</span>
        <h1>让企业业务<br />跑在智能体之上</h1>
        <p>
          BizBuddy 以 AgentScope 2.0 为内核：入口智能体「小Z」统一接待，能准确回答的直接回答，需要领域能力的按专家清单路由；
          专家智能体带着只读工具、知识库与技能作业，把自己的判断直接回给提问人。写操作一律先经人工确认，
          对话内容过安全审核，每一轮编排、每次工具调用与每一次权限决策都留痕。
        </p>
        <div class="hero-actions">
          <el-button v-if="chatVisible" type="primary" @click="goPage('/bizbuddy/agent/chat')">开始对话</el-button>
          <el-button plain @click="scrollToCapability">查看 AI 能力</el-button>
        </div>
      </div>

      <aside class="welcome-card">
        <img class="welcome-avatar" :src="avatar" alt="用户头像" />
        <div class="welcome-line">
          <strong>{{ greeting }}，{{ nickname || '欢迎回来' }}</strong>
          <span>{{ todayText }}</span>
        </div>
        <p class="welcome-hint">
          直接去任务工作台找小Z。也可以在输入框的「+」里挑选专家、场景包、工具、技能与 MCP 工具——
          这一轮对话会按你的选择装配，选中的资源会一并交给被路由的专家。
        </p>
      </aside>
    </section>

    <section class="section-card flow-panel">
      <div class="section-head">
        <div>
          <span class="section-kicker">工作方式</span>
          <h2>一轮对话在平台里怎么走</h2>
        </div>
        <span class="section-note">每一环都留痕，可在「编排运行」页回看决策原文</span>
      </div>
      <div class="flow-track">
        <template v-for="(step, index) in flowSteps" :key="step.title">
          <article class="flow-node">
            <span class="flow-index">{{ index + 1 }}</span>
            <h3>{{ step.title }}</h3>
            <p>{{ step.desc }}</p>
          </article>
          <span v-if="index < flowSteps.length - 1" class="flow-arrow" aria-hidden="true"></span>
        </template>
      </div>
      <p class="flow-foot">
        写工具按「允许 / 需确认 / 禁止」三档执行：「需确认」会暂停本轮、在对话里等你的确认再继续，
        「禁止」的工具不会注册给模型；专家不可用时如实降级并由小Z兜底说明，不静默失败。
      </p>
    </section>

    <section class="section-card capability-panel">
      <div class="section-head">
        <div>
          <span class="section-kicker">AI 能力</span>
          <h2>已经跑在企业里的智能能力</h2>
        </div>
        <span class="section-note">标注「可选开启」的项默认关闭，可在对应配置页开启</span>
      </div>
      <div class="capability-grid">
        <article v-for="item in capabilities" :key="item.title" class="capability-tile">
          <div class="tile-top">
            <span class="tile-icon"><svg-icon :icon-class="item.icon" /></span>
            <span class="tile-status" :class="{ optional: item.status === '可选开启' }">{{ item.status }}</span>
          </div>
          <h3>{{ item.title }}</h3>
          <p>{{ item.desc }}</p>
          <div class="tile-tags">
            <el-tag v-for="tag in item.tags" :key="tag" effect="plain" size="small">{{ tag }}</el-tag>
          </div>
        </article>
      </div>
    </section>

    <section class="section-card">
      <div class="section-head">
        <div>
          <span class="section-kicker">AI 入口</span>
          <h2>对话、装配与治理</h2>
        </div>
        <span class="section-note">权限不足的入口自动隐藏</span>
      </div>
      <div class="entry-grid">
        <button
          v-for="entry in visibleAiEntries"
          :key="entry.path"
          type="button"
          class="entry-item"
          @click="goPage(entry.path)"
        >
          <span class="entry-icon"><svg-icon :icon-class="entry.icon" /></span>
          <span class="entry-text">
            <strong>{{ entry.title }}</strong>
            <em>{{ entry.desc }}</em>
          </span>
        </button>
      </div>
    </section>

    <section class="section-card">
      <div class="section-head">
        <div>
          <span class="section-kicker">业务入口</span>
          <h2>流程与平台功能</h2>
        </div>
        <span class="section-note">智能体可以在这些流程与数据之上作业</span>
      </div>
      <div class="entry-grid">
        <button
          v-for="entry in visibleBizEntries"
          :key="entry.path"
          type="button"
          class="entry-item"
          @click="goPage(entry.path)"
        >
          <span class="entry-icon"><svg-icon :icon-class="entry.icon" /></span>
          <span class="entry-text">
            <strong>{{ entry.title }}</strong>
            <em>{{ entry.desc }}</em>
          </span>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup name="Index" lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useUserStore } from '@/store/modules/user';

const router = useRouter();
const userStore = useUserStore();
const nickname = computed(() => userStore.nickname);
const avatar = computed(() => userStore.avatar);

const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '早上好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
});

const todayText = computed(() => {
  const now = new Date();
  const week = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][now.getDay()];
  return `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 ${week}`;
});

/** 一轮对话的编排链路：与 docs/16 的入口智能体与专家路由口径一致 */
const flowSteps = [
  {
    title: '提问与装配',
    desc: '在任务工作台与小Z对话；可用「+」选定专家、场景包、工具、技能与 MCP 工具，本轮按你的选择装配。'
  },
  {
    title: '小Z 判定',
    desc: '能准确回答就自己答，不再往下走；需要领域能力时从候选专家清单里选一位，提交路由决策并给出任务上下文。'
  },
  {
    title: '专家作业',
    desc: '专家带着只读工具、知识库与技能完成作业，正文直接回给提问人；小Z 不再转述，避免事实被二次改写。'
  }
];

/** AI 能力：status 区分已落地能力与需在配置页开启的能力 */
const capabilities = [
  {
    icon: 'star',
    status: '已落地',
    title: '入口智能体与专家路由',
    desc: '小Z 统一接待并判定「自答还是交专家」；同一会话内还能指定某位专家接管，专家上下文与主会话隔离。',
    tags: ['小Z', '专家路由']
  },
  {
    icon: 'education',
    status: '已落地',
    title: '知识库与检索增强',
    desc: '覆盖建库、切分、向量化到召回调优的全流程，向量落在 Milvus；检索一律按提问用户的生效范围过滤。',
    tags: ['RAG', 'Milvus']
  },
  {
    icon: 'skill',
    status: '已落地',
    title: '技能体系',
    desc: '技能以 SKILL.md 正文与参考资料成对维护在平台数据库，按「智能体 × 技能」绑定或会话资源集选中后装配生效；不做草案与晋升，入库即正式技能。',
    tags: ['SKILL.md', '技能仓库']
  },
  {
    icon: 'tool',
    status: '已落地',
    title: '工具与 MCP 双向接入',
    desc: '业务工具按「允许 / 需确认 / 禁止」三档授权注册；MCP 客户端接入外部系统、服务端把只读工具对外提供，第三方 MCP 回参同样过脱敏。',
    tags: ['MCP', '三档授权']
  },
  {
    icon: 'shield',
    status: '可选开启',
    title: '安全合规',
    desc: '输入 / 输出 / 渠道出站 / 工具结果 / 知识库入库 / MCP 出口逐段过审，含 PII 脱敏与提示词注入防护；审核引擎可选本地词库或外部内容安全 API，内容审核默认关闭可灰度开启。',
    tags: ['内容审核', 'PII 脱敏']
  },
  {
    icon: 'lock',
    status: '已落地',
    title: '人工确认与权限治理',
    desc: '写操作走框架原生「暂停 → 人工确认 → 恢复」；工具授权按用户隔离，「记住该工具」不会影响其他用户。',
    tags: ['HITL', '权限决策']
  },
  {
    icon: 'job',
    status: '已落地',
    title: '无人值守运行',
    desc: '定时任务按 Cron 派发、以指定运行身份用户执行；飞书 / 企业微信 / GitHub 渠道免登录回调接入，写工具一律拒绝。',
    tags: ['定时任务', '渠道接入']
  },
  {
    icon: 'monitor',
    status: '已落地',
    title: '编排审计与全链路观测',
    desc: '会话与消息、工具入参出参、权限决策、编排路由（命中 / 降级 / 耗时）与执行期快照，五类留痕可相互深链排障。',
    tags: ['审计', '观测']
  },
  {
    icon: 'category',
    status: '已落地',
    title: '场景包与资源选择',
    desc: '按角色或部门把工具、MCP 工具与技能打成场景包，对话内随时切换；选中的资源会一并传给被路由的专家。',
    tags: ['场景包', '资源集']
  },
  {
    icon: 'box',
    status: '可选开启',
    title: '长期记忆与计划模式',
    desc: '长期记忆按用户隔离存放于工作区，计划模式先只读设计后执行；两者均按需开启。',
    tags: ['长期记忆', '计划模式']
  }
];

/** AI 入口：与后台菜单保持一致，权限不足的入口自动隐藏 */
const aiEntries = [
  { icon: 'message', title: '任务工作台', desc: '与小Z对话，工具调用与确认', path: '/bizbuddy/agent/chat' },
  { icon: 'star', title: '小Z配置', desc: '入口智能体的模型与提示词', path: '/bizbuddy/agent/entry' },
  { icon: 'model', title: '专家配置', desc: '领域专家的模型、提示词与绑定', path: '/bizbuddy/agent/expert' },
  { icon: 'skill', title: '技能仓库', desc: '维护 SKILL.md 与参考资料', path: '/bizbuddy/agent/skill' },
  { icon: 'category', title: '场景包', desc: '按角色打包工具、MCP 与技能', path: '/bizbuddy/agent/pack' },
  { icon: 'time-range', title: '定时任务', desc: '让智能体按 Cron 无人值守运行', path: '/bizbuddy/agent/task' },
  { icon: 'documentation', title: '知识库管理', desc: '建库、切分与向量模型', path: '/bizbuddy/kb/base' },
  { icon: 'search', title: '检索测试', desc: '召回效果调试（Top-K 与阈值）', path: '/bizbuddy/kb/retrieval' },
  { icon: 'server', title: 'MCP 客户端', desc: '接入外部系统的能力', path: '/bizbuddy/integration/mcp-client' },
  { icon: 'chart', title: '编排运行', desc: '路由命中率、降级率与耗时', path: '/bizbuddy/audit/routeRun' }
];

/** 业务入口：智能体可以在这些流程与数据之上作业 */
const bizEntries = [
  { icon: 'my-task', title: '我的待办', desc: '当前需要我处理的流程任务', path: '/task/taskWaiting' },
  { icon: 'guide', title: '我发起的', desc: '我提交的业务流程单据', path: '/task/myDocument' },
  { icon: 'process-definition', title: '流程定义', desc: '设计并发布业务流程', path: '/workflow/processDefinition' },
  { icon: 'category', title: '流程分类', desc: '维护业务流程分类目录', path: '/workflow/category' },
  { icon: 'tree-table', title: '流程实例', desc: '查看流程运行情况', path: '/workflow/processMonitor/processInstance' },
  { icon: 'job', title: '任务调度中心', desc: '定时任务与重试管控', path: '/monitor/snailjob' },
  { icon: 'user', title: '用户管理', desc: '账号与部门归属', path: '/system/user' },
  { icon: 'peoples', title: '角色管理', desc: '角色与权限分配', path: '/system/role' }
];

/** 依据当前用户已注册的路由过滤，避免出现无效入口 */
const resolvable = (entry: { path: string }) => !!router.resolve(entry.path).name;
const visibleAiEntries = computed(() => aiEntries.filter(resolvable));
const visibleBizEntries = computed(() => bizEntries.filter(resolvable));
/** 主页主按钮「开始对话」仅在任务工作台可见时出现 */
const chatVisible = computed(() => resolvable({ path: '/bizbuddy/agent/chat' }));

const goPage = (path: string) => {
  router.push(path);
};

const scrollToCapability = () => {
  document.querySelector('.capability-panel')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
};
</script>

<style lang="scss" scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero-panel,
.section-card {
  border-radius: 28px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-surface-bg);
  box-shadow: var(--app-shadow-sm);
  backdrop-filter: blur(18px);
}

.hero-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(280px, 0.7fr);
  gap: 18px;
  padding: 34px 30px;
  background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.16), transparent 32%), var(--app-surface-bg);
}

.hero-copy {
  display: flex;
  flex-direction: column;
  gap: 16px;

  h1 {
    margin: 0;
    font-size: clamp(30px, 4vw, 46px);
    line-height: 1.14;
    letter-spacing: -0.04em;
    color: var(--app-text-title);
  }

  p {
    margin: 0;
    max-width: 720px;
    color: var(--app-text-muted);
    font-size: 15px;
    line-height: 1.9;
  }
}

.hero-badge {
  display: inline-flex;
  width: fit-content;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
}

.welcome-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  border-radius: 24px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
}

.welcome-avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid rgba(53, 109, 255, 0.24);
}

.welcome-line {
  display: flex;
  flex-direction: column;
  gap: 6px;

  strong {
    color: var(--app-text-title);
    font-size: 20px;
    letter-spacing: -0.02em;
  }

  span {
    color: var(--app-text-muted);
    font-size: 13px;
  }
}

.welcome-hint {
  margin: 0;
  padding-top: 14px;
  border-top: 1px dashed var(--app-surface-border);
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.9;
}

.section-card {
  padding: 24px;
}

.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;

  h2 {
    margin: 6px 0 0;
    color: var(--app-text-title);
    font-size: 24px;
    letter-spacing: -0.03em;
  }
}

.section-kicker {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.section-note {
  color: var(--app-text-muted);
  font-size: 12px;
}

/* 编排链路 */
.flow-panel {
  background: radial-gradient(circle at top left, rgba(14, 165, 233, 0.1), transparent 30%), var(--app-surface-bg);
}

.flow-track {
  display: flex;
  align-items: stretch;
  gap: 10px;
}

.flow-node {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
  padding: 20px;
  border-radius: 22px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);

  h3 {
    margin: 0;
    color: var(--app-text-title);
    font-size: 16px;
    letter-spacing: -0.02em;
  }

  p {
    margin: 0;
    color: var(--app-text-muted);
    font-size: 13px;
    line-height: 1.85;
  }
}

.flow-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 9px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 13px;
  font-weight: 700;
}

.flow-arrow {
  align-self: center;
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-top: 2px solid var(--app-text-muted);
  border-right: 2px solid var(--app-text-muted);
  transform: rotate(45deg);
  opacity: 0.5;
}

.flow-foot {
  margin: 16px 0 0;
  padding-top: 14px;
  border-top: 1px dashed var(--app-surface-border);
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.9;
}

.capability-panel {
  background: radial-gradient(circle at top right, rgba(14, 165, 233, 0.12), transparent 30%), var(--app-surface-bg);
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.capability-tile {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px;
  border-radius: 24px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
  transition:
    transform 0.25s ease,
    box-shadow 0.25s ease,
    border-color 0.25s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--app-shadow-sm);
    border-color: rgba(53, 109, 255, 0.2);
  }

  h3 {
    margin: 0;
    color: var(--app-text-title);
    font-size: 18px;
    letter-spacing: -0.02em;
  }

  p {
    margin: 0;
    color: var(--app-text-muted);
    font-size: 14px;
    line-height: 1.85;
  }
}

.tile-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tile-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 14px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 20px;
}

.tile-status {
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(14, 165, 233, 0.14);
  color: var(--app-accent-strong);
  font-size: 12px;
  font-weight: 700;

  &.optional {
    background: var(--app-elevated-soft-bg);
    border: 1px dashed var(--app-surface-border);
    color: var(--app-text-muted);
    font-weight: 600;
  }
}

.tile-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.entry-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border-radius: 20px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
  text-align: left;
  cursor: pointer;
  font: inherit;
  transition:
    transform 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.2s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: rgba(53, 109, 255, 0.24);
    box-shadow: var(--app-shadow-sm);
  }
}

.entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 18px;
}

.entry-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;

  strong {
    color: var(--app-text-title);
    font-size: 15px;
  }

  em {
    color: var(--app-text-muted);
    font-size: 12px;
    font-style: normal;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

@media (max-width: 1100px) {
  .hero-panel {
    grid-template-columns: 1fr;
  }

  .flow-track {
    flex-direction: column;
  }

  .flow-arrow {
    align-self: center;
    transform: rotate(135deg);
  }
}

@media (max-width: 640px) {
  .hero-panel,
  .section-card {
    padding: 20px;
    border-radius: 22px;
  }

  .section-head {
    flex-direction: column;
    align-items: flex-start;
  }
}

html.dark {
  .hero-panel {
    background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.18), transparent 32%), var(--app-surface-bg);
  }

  .flow-panel {
    background: radial-gradient(circle at top left, rgba(14, 165, 233, 0.14), transparent 30%), var(--app-surface-bg);
  }

  .capability-panel {
    background: radial-gradient(circle at top right, rgba(14, 165, 233, 0.16), transparent 30%), var(--app-surface-bg);
  }
}
</style>