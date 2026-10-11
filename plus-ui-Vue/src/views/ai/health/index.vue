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

      <el-table :data="report.items" border>
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
  </div>
</template>

<script setup name="AiHealth" lang="ts">
import { getHealthReport, probeHealthItem } from '@/api/ai/health';
import type { HealthCheckItem, HealthReport, HealthStatus } from '@/api/ai/health/types';
import modal from '@/plugins/modal';

type TagType = 'success' | 'warning' | 'danger' | 'info';

type AlertType = 'success' | 'warning' | 'error' | 'info';

const loading = ref(true);
/** 正在探测的行 key（行级 loading） */
const probingKey = ref('');

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
</style>
