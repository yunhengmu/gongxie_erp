<template>
  <div class="p-2 app-container">
    <section class="ai-placeholder">
      <header class="placeholder-head">
        <span class="placeholder-badge">BizBuddy · {{ phase }}</span>
        <h2>{{ title }}</h2>
        <p>{{ summary }}</p>
      </header>

      <div class="placeholder-body">
        <h3>页面规划</h3>
        <ul>
          <li v-for="item in items" :key="item">{{ item }}</li>
        </ul>
      </div>

      <p class="placeholder-foot">当前为能力骨架页，功能将随 AgentScope 智能体内核落地逐步开放。</p>
    </section>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  /** 页面名称 */
  title: string;
  /** 所属落地阶段 */
  phase: string;
  /** 一句话说明该页面承担什么职责 */
  summary: string;
  /** 页面能力要点 */
  items: string[];
}>();
</script>

<style lang="scss" scoped>
.ai-placeholder {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 30px;
  border-radius: 28px;
  border: 1px solid var(--app-surface-border);
  background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.14), transparent 30%), var(--app-surface-bg);
  box-shadow: var(--app-shadow-sm);
}

.placeholder-head {
  display: flex;
  flex-direction: column;
  gap: 12px;

  h2 {
    margin: 0;
    color: var(--app-text-title);
    font-size: clamp(24px, 3vw, 32px);
    letter-spacing: -0.03em;
  }

  p {
    margin: 0;
    max-width: 720px;
    color: var(--app-text-muted);
    font-size: 15px;
    line-height: 1.9;
  }
}

.placeholder-badge {
  display: inline-flex;
  width: fit-content;
  padding: 7px 13px;
  border-radius: 999px;
  background: rgba(53, 109, 255, 0.12);
  color: var(--app-accent-strong);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.placeholder-body {
  padding: 22px;
  border-radius: 22px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);

  h3 {
    margin: 0 0 12px;
    color: var(--app-text-title);
    font-size: 16px;
  }

  ul {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: 10px;
  }

  li {
    position: relative;
    padding-left: 16px;
    color: var(--app-text-muted);
    font-size: 14px;
    line-height: 1.8;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 10px;
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--app-accent-strong);
      box-shadow: 0 0 0 5px rgba(53, 109, 255, 0.12);
    }
  }
}

.placeholder-foot {
  margin: 0;
  padding-top: 16px;
  border-top: 1px dashed var(--app-surface-border);
  color: var(--app-text-muted);
  font-size: 12px;
}

@media (max-width: 640px) {
  .ai-placeholder {
    padding: 20px;
    border-radius: 22px;
  }
}

html.dark {
  .ai-placeholder {
    background: radial-gradient(circle at top left, rgba(53, 109, 255, 0.16), transparent 30%), var(--app-surface-bg);
  }
}
</style>