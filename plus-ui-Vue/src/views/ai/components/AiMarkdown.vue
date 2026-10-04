<template>
  <div class="ai-markdown" v-html="html"></div>
</template>

<script setup lang="ts">
import hljs from 'highlight.js/lib/common';
import MarkdownIt from 'markdown-it';

const props = defineProps<{
  /** Markdown 源文本 */
  content: string;
}>();

/** 转义纯文本，避免代码块未高亮时破坏结构 */
const escapeHtml = (str: string) =>
  str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/**
 * 拆开被模型压在同一行里的结构性标记。
 *
 * <p>如 `## 原理###1.特点`、`## 二、核心特点- 不需要建立连接`，
 * 前者是标题被压行，后者是标题与首条列表被压行。</p>
 */
const splitInlineMarkers = (line: string) =>
  line
    // 行中间的标题标记（2 个及以上 #）：原理###1.特点 → 原理 / ###1.特点
    .replace(/([^\s#])(#{2,6})(?=[^\s#])/g, '$1\n$2')
    // 行中间紧跟中文的列表标记（要求标记后确有空格，避免误伤 1-3、10-20 这类区间）
    .replace(/([\u4e00-\u9fa5）】])([-+*])\s+(?=\S)/g, '$1\n$2 ')
    .replace(/([\u4e00-\u9fa5）】])(\d{1,9}[.)])\s+(?=\S)/g, '$1\n$2 ');

/**
 * 修复大模型输出中不合 CommonMark 规范的 Markdown 标记。
 *
 * <p>模型常把标题压进上一行、标记后不留空格（如 `## 原理###1.特点正文`），
 * 这类写法在 CommonMark 中不是标题，会被原样输出成 `###`。此处按行做机械修复。</p>
 */
const normalizeMarkdown = (source: string) => {
  const lines: string[] = [];
  // 围栏代码块内一律不改动，避免破坏代码内容
  let fence: string | undefined;
  for (const raw of source.split('\n')) {
    const fenceMatch = raw.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (fenceMatch) {
      fence = fence === undefined ? fenceMatch[1].charAt(0) : undefined;
      lines.push(raw);
      continue;
    }
    if (fence !== undefined) {
      lines.push(raw);
      continue;
    }
    lines.push(...fixLine(raw));
  }
  return lines.join('\n');
};

/**
 * 逐行修复（可能因拆行返回 1~2 行）。
 *
 * @param raw 原始行
 * @returns 修复后的行
 */
const fixLine = (raw: string): string[] => {
  const out: string[] = [];
  for (const line of splitInlineMarkers(raw).split('\n')) {
    // 标题标记后补空格：###标题 → ### 标题
    const heading = line.replace(/^(\s{0,3}#{1,6})(?=[^\s#])/, '$1 ');
    // 标题行内紧跟中文、且标记后无空格的列表（### 三、性能特点-在……）单独拆行
    // 标记后要求紧跟中文或「【，避免把 **粗体**、*斜体* 误判成列表
    const glued = heading.replace(
      /^(\s{0,3}#{1,6}\s+.*?[\u4e00-\u9fa5）】])([-+*])(?=[\u4e00-\u9fa5「【])/,
      '$1\n$2 '
    );
    for (const part of glued.split('\n')) {
      // 列表标记后补空格（避开 **粗体**、--- 分隔线、4.0.6 这类版本号）
      const listed = part
        .replace(/^(\s*)([-+*])(?![\s\-+*])/, '$1$2 ')
        .replace(/^(\s*\d{1,9})([.)])(?=[^\s\d])/, '$1$2 ');
      // 标题行内仍粘着正文时，在首个句末标点后断行，避免整段正文被当成标题
      const bodySplit = listed.match(/^(\s{0,3}#{1,6}\s+\S.*?[。！？；])(\S.*)$/);
      if (bodySplit) {
        out.push(bodySplit[1], bodySplit[2]);
      } else {
        out.push(listed);
      }
    }
  }
  return out;
};

// markdown-it 实例在模块作用域只创建一次，避免随组件重建
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight: (code: string, lang: string) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre><code class="hljs language-${lang}">${
          hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
        }</code></pre>`;
      } catch {
        // 高亮失败时回退为纯文本
      }
    }
    return `<pre><code class="hljs">${escapeHtml(code)}</code></pre>`;
  }
});

/** 触发预览的围栏语言（模型交付单文件 HTML 时的惯用标记） */
const PREVIEW_LANGS = new Set(['html', 'htm']);

/** 预览块自增序号：同一页可能有多段 HTML，用独立 id 绑定「查看源码 / 返回预览」两个 label */
let previewSeq = 0;

/**
 * 记录默认围栏渲染器，非 HTML 围栏仍走它（保留 highlight.js 高亮）。
 */
const defaultFence = md.renderer.rules.fence!;

/**
 * 把 ```html 围栏渲染为「沙箱预览 + 可切看源码」的块。
 *
 * <p>为什么不直接打开 markdown-it 的 {@code html} 选项：那是**全局**放行原始 HTML，
 * 模型输出的任意标签都会被注入页面。这里只对 html 围栏做受控处理——
 * 预览走 {@code sandbox="allow-scripts"} 的 iframe（不开 {@code allow-same-origin}，
 * 文档处于独立不透明源，拿不到父页面的 DOM / Cookie / Storage，但脚本可执行，
 * 图表运行时正需要脚本）；源码走 highlight.js 转义输出。</p>
 *
 * <p>「查看源码 / 返回预览」用纯 CSS 的 checkbox 兄弟选择器切换，不引入事件绑定——
 * 该片段由 v-html 注入，挂不上 Vue 的事件监听。</p>
 */
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const lang = (token.info || '').trim().split(/\s+/)[0].toLowerCase();
  if (!PREVIEW_LANGS.has(lang)) {
    return defaultFence(tokens, idx, options, env, self);
  }
  const id = `ai-html-preview-${++previewSeq}`;
  const code = token.content;
  const source = hljs.getLanguage('html')
    ? hljs.highlight(code, { language: 'html', ignoreIllegals: true }).value
    : escapeHtml(code);
  return (
    '<div class="html-preview">' +
    `<input type="checkbox" class="html-preview__toggle" id="${id}" checked>` +
    '<div class="html-preview__bar">' +
    '<span class="html-preview__title">HTML 预览</span>' +
    `<label class="html-preview__act html-preview__act--source" for="${id}">查看源码</label>` +
    `<label class="html-preview__act html-preview__act--preview" for="${id}">返回预览</label>` +
    '</div>' +
    `<div class="html-preview__stage"><iframe sandbox="allow-scripts" srcdoc="${escapeHtml(code)}"></iframe></div>` +
    `<pre class="html-preview__source"><code class="hljs language-html">${source}</code></pre>` +
    '</div>\n'
  );
};

const html = computed(() => md.render(normalizeMarkdown(props.content || '')));
</script>

<style scoped lang="scss">
.ai-markdown {
  color: inherit;
  font-size: 14px;
  line-height: 1.8;
  word-break: break-word;

  :deep(> :first-child) {
    margin-top: 0;
  }

  :deep(> :last-child) {
    margin-bottom: 0;
  }

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4),
  :deep(h5),
  :deep(h6) {
    margin: 18px 0 10px;
    color: var(--app-text-title);
    font-weight: 700;
    line-height: 1.4;
  }

  :deep(h1) {
    font-size: 20px;
  }

  :deep(h2) {
    font-size: 18px;
  }

  :deep(h3) {
    font-size: 16px;
  }

  :deep(p) {
    margin: 10px 0;
  }

  :deep(ul),
  :deep(ol) {
    margin: 10px 0;
    padding-left: 22px;
  }

  :deep(li) {
    margin: 4px 0;
  }

  :deep(li > ul),
  :deep(li > ol) {
    margin: 4px 0;
  }

  :deep(blockquote) {
    margin: 12px 0;
    padding: 8px 14px;
    border-left: 3px solid var(--app-accent-strong);
    background: var(--app-accent-soft);
    color: var(--app-text-muted);
    border-radius: 0 8px 8px 0;
  }

  :deep(a) {
    color: var(--app-accent-strong);
    text-decoration: none;

    &:hover {
      text-decoration: underline;
    }
  }

  :deep(code) {
    padding: 2px 6px;
    border-radius: 6px;
    background: var(--app-elevated-close-bg);
    color: var(--app-text-title);
    font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
    font-size: 13px;
  }

  :deep(pre) {
    margin: 12px 0;
    padding: 14px 16px;
    overflow-x: auto;
    border: 1px solid var(--app-surface-border);
    border-radius: 12px;
    background: var(--app-elevated-soft-bg);
  }

  :deep(pre code) {
    padding: 0;
    background: transparent;
    font-size: 13px;
    line-height: 1.7;
  }

  // ```html 代码块：默认渲染为沙箱预览（聊天页不直接放行原始 HTML，图上效果只能这样看）
  :deep(.html-preview) {
    margin: 12px 0;
    border: 1px solid var(--app-surface-border);
    border-radius: 12px;
    overflow: hidden;
    background: var(--app-elevated-soft-bg);
  }

  // checkbox 只作为「预览 / 源码」两态的状态位，不展示
  :deep(.html-preview__toggle) {
    display: none;
  }

  :deep(.html-preview__bar) {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    border-bottom: 1px solid var(--app-surface-border);
    font-size: 12px;
    color: var(--app-text-muted);
  }

  :deep(.html-preview__title) {
    font-weight: 600;
    color: var(--app-text-title);
  }

  :deep(.html-preview__act) {
    margin-left: auto;
    padding: 2px 8px;
    border: 1px solid var(--app-surface-border);
    border-radius: 6px;
    cursor: pointer;
    user-select: none;

    &:hover {
      color: var(--app-accent-strong);
      border-color: var(--app-accent-strong);
    }
  }

  :deep(.html-preview__stage) {
    // 兜底底色：生成的 HTML 自带浅色背景，未设置时也保证可读
    background: #fff;
  }

  :deep(.html-preview__stage iframe) {
    display: block;
    width: 100%;
    height: 520px;
    border: 0;
  }

  // 源码态：去掉 pre 的独立边框与留白，嵌进预览容器内
  :deep(.html-preview__source) {
    display: none;
    margin: 0;
    border: 0;
    border-radius: 0;
    background: transparent;
  }

  :deep(.html-preview__act--preview) {
    display: none;
  }

  // 预览态（默认选中）：显示 iframe + 「查看源码」
  :deep(.html-preview__toggle:checked ~ .html-preview__bar .html-preview__act--source) {
    display: inline-block;
  }

  :deep(.html-preview__toggle:checked ~ .html-preview__bar .html-preview__act--preview) {
    display: none;
  }

  // 源码态：显示源码 + 「返回预览」
  :deep(.html-preview__toggle:not(:checked) ~ .html-preview__bar .html-preview__act--source) {
    display: none;
  }

  :deep(.html-preview__toggle:not(:checked) ~ .html-preview__bar .html-preview__act--preview) {
    display: inline-block;
  }

  :deep(.html-preview__toggle:not(:checked) ~ .html-preview__stage) {
    display: none;
  }

  :deep(.html-preview__toggle:not(:checked) ~ .html-preview__source) {
    display: block;
  }

  :deep(table) {
    width: 100%;
    margin: 12px 0;
    border-collapse: collapse;
    font-size: 13px;
  }

  :deep(th),
  :deep(td) {
    padding: 8px 12px;
    border: 1px solid var(--app-surface-border);
    text-align: left;
  }

  :deep(th) {
    background: var(--app-elevated-soft-bg);
    color: var(--app-text-title);
    font-weight: 600;
  }

  :deep(hr) {
    margin: 16px 0;
    border: none;
    border-top: 1px solid var(--app-surface-border);
  }
}
</style>
