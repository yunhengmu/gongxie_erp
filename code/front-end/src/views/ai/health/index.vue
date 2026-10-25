<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>平台健康自检</h3>
            <p>
              一次体检：数据库 / Redis 连通、模型密钥是否配齐、向量维度是否一致、Milvus 与外部 MCP 是否可用、
              内容安全与检索重排、时区、对象存储对外域名、出厂加密密钥是否收口。
              <strong>自检本身只读、不发起外网调用</strong>；需要真实连接的项请点行内「测试」。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-radio-group v-model="healthFilter" size="small">
              <el-radio-button value="all">全部</el-radio-button>
              <el-radio-button value="attention">失败/告警</el-radio-button>
              <el-radio-button value="pass">通过</el-radio-button>
            </el-radio-group>
            <el-button v-hasPermi="['ai:health:query']" icon="List" @click="openOnboarding">
              首登清单
            </el-button>
            <el-button v-hasPermi="['ai:health:query']" icon="Refresh" :loading="loading" @click="loadReport">
              重新自检
            </el-button>
          </div>
        </div>
      </template>

      <el-alert class="health-alert" :type="overallType" :closable="false" show-icon :title="overallTitle" />

      <div class="health-summary">
        <el-tag type="success" effect="plain">通过 {{ report.passCount }}</el-tag>
        <el-tag type="warning" effect="plain">告警 {{ report.warnCount }}</el-tag>
        <el-tag type="danger" effect="plain">失败 {{ report.failCount }}</el-tag>
        <el-tag type="info" effect="plain">待探测 {{ report.unknownCount }}</el-tag>
        <span class="health-summary__time">自检时刻：{{ report.checkedAt || '-' }}</span>
      </div>

      <el-table :data="visibleItems" border empty-text="没有符合筛选的检查项">
        <el-table-column prop="name" label="检查项" min-width="180" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="340" show-overflow-tooltip />
        <el-table-column label="修复建议" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">{{ row.suggestion || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.probeSupported"
              v-hasPermi="['ai:health:probe']"
              link
              type="primary"
              icon="Connection"
              :loading="probingKey === row.key"
              @click="handleProbe(row)"
            >
              测试
            </el-button>
            <span v-else class="health-empty">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 首登清单：与任务工作台的卡片同源（GET /ai/onboarding/checklist），不受「已完成」隐藏限制 -->
    <el-drawer v-model="onboardingVisible" title="首登清单" size="420px">
      <div v-loading="onboardingLoading" class="onboarding-drawer">
        <div v-if="onboarding" class="onboarding-drawer__summary">
          <el-progress :percentage="onboardingPercent" :stroke-width="8" :show-text="false" />
          <span>已完成 {{ onboarding.doneCount }}/{{ onboarding.requiredCount }} 项</span>
        </div>
        <div v-if="onboarding" class="onboarding-drawer__list">
          <div
            v-for="item in onboarding.items"
            :key="item.key"
            class="onboarding-row"
            :class="{ 'is-done': item.done }"
            role="button"
            tabindex="0"
            @click="handleOnboardingItem(item)"
            @keydown.enter.prevent="handleOnboardingItem(item)"
          >
            <el-icon class="onboarding-row__status">
              <CircleCheckFilled v-if="item.done" />
              <CircleCheck v-else />
            </el-icon>
            <div class="onboarding-row__body">
              <div class="onboarding-row__title">{{ item.title }}</div>
              <div class="onboarding-row__desc">{{ item.description }}</div>
            </div>
            <el-icon v-if="!item.done" class="onboarding-row__arrow"><ArrowRight /></el-icon>
          </div>
        </div>
        <el-empty v-else-if="!onboardingLoading" description="清单加载失败" :image-size="70" />
      </div>
      <template #footer>
        <el-button @click="onboardingVisible = false">关闭</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="AiHealth" lang="ts">
import { getHealthReport, probeHealthItem } from '@/api/ai/health';
import type { HealthCheckItem, HealthReport, HealthStatus } from '@/api/ai/health/types';
import { getOnboardingChecklist } from '@/api/ai/onboarding';
import type { OnboardingItem, OnboardingReport } from '@/api/ai/onboarding/types';
import modal from '@/plugins/modal';

type TagType = 'success' | 'warning' | 'danger' | 'info';

type AlertType = 'success' | 'warning' | 'error' | 'info';

const loading = ref(true);
/** 正在探测的行 key（行级 loading） */
const probingKey = ref('');
/** 首登清单抽屉 */
const onboardingVisible = ref(false);
const onboardingLoading = ref(false);
const onboarding = ref<OnboardingReport | null>(null);
const router = useRouter();

/** 首登清单完成百分比 */
const onboardingPercent = computed(() => {
  const data = onboarding.value;
  if (!data || !data.requiredCount) {
    return 0;
  }
  return Math.round((data.doneCount / data.requiredCount) * 100);
});

/** 打开首登清单并实时刷新 */
const openOnboarding = async () => {
  onboardingVisible.value = true;
  onboardingLoading.value = true;
  try {
    const res = await getOnboardingChecklist();
    onboarding.value = res.data ?? null;
  } catch {
    onboarding.value = null;
  } finally {
    onboardingLoading.value = false;
  }
};

/** 点击未完成项：跳到对应页面（首轮对话跳任务工作台） */
const handleOnboardingItem = (item: OnboardingItem) => {
  if (item.done) {
    return;
  }
  const targets: Record<string, string> = {
    'chat-model': '/bizbuddy/model/config',
    'embedding-model': '/bizbuddy/model/config',
    document: '/bizbuddy/kb/doc',
    'first-chat': '/bizbuddy/agent/chat'
  };
  const target = targets[item.key];
  if (!target) {
    return;
  }
  onboardingVisible.value = false;
  void router.push(target);
};

/** 报告（首屏占位，加载后以服务端结果为准） */
const report = ref<HealthReport>({
  overall: 'PASS',
  total: 0,
  passCount: 0,
  warnCount: 0,
  failCount: 0,
  unknownCount: 0,
  checkedAt: '',
  items: []
});

/** 状态 → 中文标签 */
const statusLabel = (status: HealthStatus) => {
  if (status === 'PASS') return '通过';
  if (status === 'WARN') return '告警';
  if (status === 'FAIL') return '失败';
  return '待探测';
};

/** 健康检查筛选：默认全部，失败/告警优先排序 */
const healthFilter = ref<'all' | 'attention' | 'pass'>('all');

/** 严重度排序：失败 → 告警 → 待探测 → 通过 */
const SEVERITY: Record<HealthStatus, number> = {
  FAIL: 0,
  WARN: 1,
  UNKNOWN: 2,
  PASS: 3
};

/** 按筛选条件与严重度排序后的检查项 */
const visibleItems = computed(() => {
  const items = [...report.value.items];
  const filtered = items.filter((item) => {
    if (healthFilter.value === 'attention') {
      return item.status === 'FAIL' || item.status === 'WARN';
    }
    if (healthFilter.value === 'pass') {
      return item.status === 'PASS';
    }
    return true;
  });
  return filtered.toSorted((left, right) => (SEVERITY[left.status] ?? 9) - (SEVERITY[right.status] ?? 9));
});

/** 状态 → 标签配色 */
const statusType = (status: HealthStatus): TagType => {
  if (status === 'PASS') return 'success';
  if (status === 'WARN') return 'warning';
  if (status === 'FAIL') return 'danger';
  return 'info';
};

/** 整体结论 → 提示条配色 */
const overallType = computed<AlertType>(() => {
  if (report.value.overall === 'FAIL') return 'error';
  if (report.value.overall === 'WARN') return 'warning';
  return 'success';
});

/** 整体结论 → 提示文案 */
const overallTitle = computed(() => {
  const data = report.value;
  if (data.overall === 'FAIL') {
    return `存在 ${data.failCount} 项失败：请按「修复建议」逐项处理后再重新自检`;
  }
  if (data.overall === 'WARN') {
    return `存在 ${data.warnCount} 项告警：不影响主流程，建议按建议处理`;
  }
  return '全部检查项通过；标记为「待探测」的项需点行内「测试」做真实连接验证。';
});

/** 本地重算汇总（探测后不重新拉全量） */
const recompute = () => {
  const items = report.value.items;
  report.value.total = items.length;
  report.value.passCount = items.filter((item) => item.status === 'PASS').length;
  report.value.warnCount = items.filter((item) => item.status === 'WARN').length;
  report.value.failCount = items.filter((item) => item.status === 'FAIL').length;
  report.value.unknownCount = items.filter((item) => item.status === 'UNKNOWN').length;
  if (report.value.failCount > 0) {
    report.value.overall = 'FAIL';
  } else if (report.value.warnCount > 0) {
    report.value.overall = 'WARN';
  } else {
    report.value.overall = 'PASS';
  }
};

/** 执行一次全量自检 */
const loadReport = async () => {
  loading.value = true;
  try {
    const res = await getHealthReport();
    if (res.data) {
      report.value = res.data;
    }
  } finally {
    loading.value = false;
  }
};

/** 对单项做真实连接探测 */
const handleProbe = async (row: HealthCheckItem) => {
  if (!row.probeTarget) {
    return;
  }
  probingKey.value = row.key;
  try {
    const res = await probeHealthItem({ target: row.probeTarget, id: row.probeId });
    const result = res.data;
    if (!result) {
      return;
    }
    row.status = result.status;
    row.detail = result.detail;
    row.suggestion = result.suggestion;
    row.elapsedMs = result.elapsedMs;
    recompute();
    if (result.status === 'PASS') {
      modal.msgSuccess(`${row.name}：${result.detail}`);
    } else {
      modal.msgError(`${row.name}：${result.detail}`);
    }
  } finally {
    probingKey.value = '';
  }
};

onMounted(loadReport);
</script>

<style scoped lang="scss">
.health-alert {
  margin-bottom: 12px;
}

.health-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;

  &__time {
    margin-left: auto;
    color: var(--app-text-muted);
    font-size: 12px;
  }
}

.health-empty {
  color: var(--app-text-muted);
}

/* 首登清单抽屉 */
.onboarding-drawer__summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  color: var(--app-text-muted);
  font-size: 12px;

  .el-progress {
    flex: 1;
  }
}

.onboarding-drawer__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.onboarding-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
  outline: none;
  transition: background-color 0.15s ease;

  &:hover,
  &:focus-visible {
    background: var(--el-fill-color-light, #f5f7fa);
  }

  &.is-done {
    cursor: default;

    &:hover {
      background: transparent;
    }
  }

  &__status {
    flex: 0 0 auto;
    font-size: 17px;
    color: var(--el-color-warning, #e6a23c);
  }

  &__body {
    min-width: 0;
    flex: 1;
  }

  &__title {
    font-size: 13px;
    color: var(--el-text-color-primary, #303133);
  }

  &__desc {
    margin-top: 2px;
    font-size: 12px;
    color: var(--el-text-color-secondary, #909399);
  }

  &__arrow {
    flex: 0 0 auto;
    color: var(--el-text-color-placeholder, #c0c4cc);
  }
}

.onboarding-row.is-done .onboarding-row__status {
  color: var(--el-color-success, #67c23a);
}

.onboarding-row.is-done .onboarding-row__title {
  color: var(--el-text-color-secondary, #909399);
}
</style>
