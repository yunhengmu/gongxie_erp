<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="search-panel">
      <template #header>
        <div class="panel-heading">
          <div>
            <h3>检索测试</h3>
            <p>输入问题，查看 Top-K 命中切片与相似度得分，用于上线前验证召回质量。</p>
          </div>
        </div>
      </template>
      <el-form :model="query" label-width="90px" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="知识库">
              <el-select v-model="query.kbId" placeholder="请选择知识库" style="width: 100%">
                <el-option v-for="item in kbOptions" :key="item.id" :label="item.kbName" :value="item.id">
                  <span>{{ item.kbName }}</span>
                  <span class="option-meta">{{ item.dimension }} 维 · {{ item.chunkSize }} 字切片</span>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="召回条数">
              <el-input-number v-model="query.topK" :min="1" :max="50" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :md="6">
            <el-form-item label="相似度阈值">
              <el-input-number
                v-model="query.threshold"
                :min="0"
                :max="1"
                :step="0.05"
                :precision="3"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="查询内容">
          <el-input
            v-model="query.query"
            type="textarea"
            :rows="3"
            placeholder="例如：Wireshark 的安装步骤是什么？"
            @keyup.ctrl.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" :loading="loading" @click="handleSearch">开始检索</el-button>
          <el-button icon="Refresh" @click="handleReset">重置</el-button>
          <span class="form-tip">Ctrl + Enter 快捷检索；阈值与条数留空时取知识库配置</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>命中结果</h3>
            <p v-if="searched">
              共命中 {{ hits.length }} 条<template v-if="hits.length"> ，最高相似度 {{ formatScore(hits[0]?.score) }}</template>
            </p>
            <p v-else>尚未检索</p>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="hit-list">
        <el-empty v-if="!hits.length" :description="searched ? emptyTip : '请在上方输入查询内容后点击「开始检索」'" />
        <div v-for="(item, index) in hits" :key="index" class="hit-item">
          <div class="hit-head">
            <div class="hit-rank">#{{ index + 1 }}</div>
            <el-tag :type="scoreType(item.score)" effect="dark">{{ formatScore(item.score) }}</el-tag>
            <span class="hit-source">
              <el-icon><Document /></el-icon>
              {{ item.docName || '未知文档' }}
              <template v-if="item.headingPath">· §{{ item.headingPath }}</template>
              <template v-if="item.pageNo !== undefined && item.pageNo !== null">· p.{{ item.pageNo }}</template>
              <template v-if="item.chunkIndex !== undefined && item.chunkIndex !== null">· 切片 #{{ item.chunkIndex }}</template>
            </span>
          </div>
          <div class="hit-content">{{ item.content }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup name="AiKbRetrieval" lang="ts">
import { visibleKnowledgeBaseOptions } from '@/api/ai/kb/base';
import type { KnowledgeBaseVO } from '@/api/ai/kb/base/types';
import { testRetrieval } from '@/api/ai/kb/retrieval';
import type { RetrievalHitVO } from '@/api/ai/kb/retrieval/types';
import { useLoading } from '@/hooks/async/useLoading';
import modal from '@/plugins/modal';

const route = useRoute();
const router = useRouter();

const kbOptions = ref<KnowledgeBaseVO[]>([]);
const hits = ref<RetrievalHitVO[]>([]);
const searched = ref(false);
const emptyTip = ref('没有召回结果，可尝试降低相似度阈值或先对文档执行「向量化」');

const { loading, withLoading } = useLoading(false);

const query = reactive<{ kbId?: string | number; query?: string; topK?: number; threshold?: number }>({
  kbId: undefined,
  query: undefined,
  topK: undefined,
  threshold: undefined
});

/** 相似度展示 */
const formatScore = (score?: number) => (score === undefined || score === null ? '-' : score.toFixed(4));

/** 相似度标签样式：越高越绿 */
const scoreType = (score?: number) => {
  if (score === undefined || score === null) {
    return 'info';
  }
  if (score >= 0.8) {
    return 'success';
  }
  return score >= 0.6 ? 'warning' : 'info';
};

/** 加载知识库下拉 */
const loadKbOptions = async () => {
  const res = await visibleKnowledgeBaseOptions();
  kbOptions.value = res.data ?? [];
  const routeKbId = route.query.kbId as string | undefined;
  if (routeKbId) {
    query.kbId = routeKbId;
  } else if (!query.kbId && kbOptions.value.length === 1) {
    query.kbId = kbOptions.value[0].id;
  }
};

/** 执行检索 */
const handleSearch = async () => {
  if (!query.kbId) {
    modal.msgWarning('请先选择知识库');
    return;
  }
  if (!query.query || !query.query.trim()) {
    modal.msgWarning('请输入查询内容');
    return;
  }
  await withLoading(async () => {
    const res = await testRetrieval({
      kbId: query.kbId,
      query: query.query?.trim(),
      topK: query.topK,
      threshold: query.threshold
    });
    hits.value = res.data ?? [];
    searched.value = true;
    if (!hits.value.length) {
      modal.msgWarning('没有召回结果，可尝试降低相似度阈值，或确认文档已完成向量化');
    }
  });
};

/** 重置 */
const handleReset = () => {
  query.query = undefined;
  query.topK = undefined;
  query.threshold = undefined;
  hits.value = [];
  searched.value = false;
};

/** 从知识库页跳转进入时，自动切到该库 */
onMounted(async () => {
  await loadKbOptions();
  const routeKbId = route.query.kbId as string | undefined;
  if (routeKbId) {
    router.replace({ path: '/bizbuddy/kb/retrieval' });
  }
});
</script>

<style scoped lang="scss">
.panel-heading {
  h3 {
    margin: 0;
    color: var(--app-text-title);
    font-size: 16px;
  }

  p {
    margin: 6px 0 0;
    color: var(--app-text-muted);
    font-size: 13px;
  }
}

.form-tip {
  margin-left: 12px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.option-meta {
  float: right;
  margin-left: 16px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.hit-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 120px;
}

.hit-item {
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid var(--app-surface-border);
  background: var(--app-elevated-soft-bg);
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.hit-rank {
  color: var(--app-accent-strong);
  font-weight: 700;
  font-size: 13px;
}

.hit-source {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.hit-content {
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>