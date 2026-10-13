<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>结构化决策路由</h3>
            <p>
              启用后由决策模型（百炼 / TypeSafe System One 协议）承担「从候选中选谁」这一步判断；判断结果先留痕，
              不影响用户可见行为。关闭时路由完全由小Z 自行判定（零外呼，与现状一致）。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['ai:runtime:jev:query']" icon="Refresh" @click="handleRefresh">刷新</el-button>
            <el-button
              v-hasPermi="['ai:health:probe']"
              icon="Connection"
              :loading="probing"
              @click="handleProbe"
            >
              连通性探测
            </el-button>
            <el-button
              v-hasPermi="['ai:runtime:jev:edit']"
              type="primary"
              icon="Check"
              :loading="saving"
              @click="handleSave"
            >
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="config-alert"
        type="warning"
        :closable="false"
        show-icon
        title="出境前强制 PII 脱敏，不可关闭。决策模型的服务地址 / 模型标识 / 密钥统一在「模型配置」维护，本页只做引用。"
      />

      <el-form ref="formRef" :model="config" label-width="220px" class="config-form">
        <el-form-item label="运行模式">
          <el-radio-group v-model="mode">
            <el-radio-button value="off">关闭</el-radio-button>
            <el-radio-button value="shadow">影子</el-radio-button>
            <el-radio-button value="gate">接管</el-radio-button>
          </el-radio-group>
          <div class="form-tip-block">{{ modeTip }}</div>
        </el-form-item>

        <el-form-item label="决策模型">
          <el-select
            v-model="config.modelConfigId"
            placeholder="请先到「模型配置」登记决策模型（类型选「决策模型」）"
            clearable
            style="width: 420px"
          >
            <el-option
              v-for="item in decisionModels"
              :key="String(item.id)"
              :label="`${item.configName} · ${item.modelCode}`"
              :value="item.id"
            />
          </el-select>
          <div v-if="!decisionModels.length" class="form-tip-block">
            当前没有启用中的决策模型：请先到「模型配置」新增一条记录，类型选「决策模型」，
            服务地址填百炼 compatible-mode 地址（自动拼 /v1/systemone），并配置 API 密钥。
          </div>
          <span v-else class="form-tip">
            决策模型的服务地址 / 模型标识 / 密钥统一由「模型配置」管理；开启运行模式时必须选择。
          </span>
        </el-form-item>
        <el-form-item label="判定超时（秒）">
          <el-input-number v-model="config.timeoutSeconds" :min="1" :max="120" controls-position="right" style="width: 220px" />
          <span class="form-tip">判定在路由关键路径上，需短于「模型配置」的通用超时</span>
        </el-form-item>
        <el-form-item label="基础置信度阈值">
          <span class="form-tip" style="margin: 0 8px 0 0">候选 ≤5</span>
          <el-input-number
            v-model="config.confidenceThreshold"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            controls-position="right"
            style="width: 120px"
          />
          <span class="form-tip" style="margin: 0 8px">6~10</span>
          <el-input-number
            v-model="config.confidenceThresholdMedium"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            controls-position="right"
            style="width: 120px"
          />
          <span class="form-tip" style="margin: 0 8px">&gt;10</span>
          <el-input-number
            v-model="config.confidenceThresholdLarge"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            controls-position="right"
            style="width: 120px"
          />
          <div class="form-tip-block">
            仅「接管」档使用。闭集概率在 N 个选项上归一化，候选越多单选概率天然越低，故按候选数分档取值
            （建议用影子期数据校准），达标后再叠加 margin 二次过滤。
          </div>
        </el-form-item>
        <el-form-item label="margin 二次过滤">
          <el-input-number
            v-model="config.marginRatio"
            :min="1"
            :max="20"
            :step="0.1"
            :precision="1"
            controls-position="right"
            style="width: 220px"
          />
          <span class="form-tip">
            要求 top1 / top2 ≥ 该比值，1.0 等价不启用；用于压掉「勉强第一」型误接管（如 0.76/0.70）
          </span>
        </el-form-item>
        <el-form-item label="粘性路由">
          <el-switch v-model="config.stickyRoutingEnabled" />
          <span class="form-tip">追问场景跳过判定、直接复用上轮专家（本档不外呼，故不涉 PII 出境）</span>
        </el-form-item>
        <el-form-item v-if="config.stickyRoutingEnabled" label="粘性触发条件">
          <span class="form-tip" style="margin: 0 8px 0 0">上轮置信度 ≥</span>
          <el-input-number
            v-model="config.stickyMinConfidence"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            controls-position="right"
            style="width: 130px"
          />
          <span class="form-tip" style="margin: 0 8px">且本轮字数 ≤</span>
          <el-input-number
            v-model="config.stickyMaxQueryLength"
            :min="1"
            :max="200"
            controls-position="right"
            style="width: 120px"
          />
          <span class="form-tip">字，且本轮未点名专家、上轮来源为「决策模型」、上轮专家仍在本轮候选内</span>
        </el-form-item>
        <el-form-item label="状态持久化">
          <el-switch v-model="config.persistProbabilities" />
          <span class="form-tip">落库概率分布（影子期校准用），接管稳定后可关闭以省存储</span>
        </el-form-item>
        <el-form-item label="容错回落">
          <el-switch v-model="config.failOpen" />
          <span class="form-tip">判定不可用时回落工具决策（恒定回落，不新增失败面）</span>
        </el-form-item>
        <el-form-item label="state 字符上限">
          <el-input-number v-model="config.stateMaxChars" :min="100" :max="65536" :step="100" controls-position="right" style="width: 220px" />
          <span class="form-tip">外呼文本前置保护，防止超长被拒</span>
        </el-form-item>
        <el-form-item label="失败熔断">
          <el-switch v-model="config.circuitBreakerEnabled" />
          <span class="form-tip">连续失败达阈值后暂停判定，提供方宕机期间不再每轮付超时</span>
        </el-form-item>
        <el-form-item v-if="config.circuitBreakerEnabled" label="熔断阈值 / 暂停">
          <el-input-number
            v-model="config.circuitBreakerThreshold"
            :min="1"
            :max="100"
            controls-position="right"
            style="width: 130px"
          />
          <span class="form-tip" style="margin: 0 8px">次失败后，暂停</span>
          <el-input-number
            v-model="config.circuitBreakerPauseSeconds"
            :min="1"
            :max="86400"
            :step="30"
            controls-position="right"
            style="width: 150px"
          />
          <span class="form-tip">秒（暂停期满后每 30 秒只放一个探测请求）</span>
        </el-form-item>

        <el-form-item label="PII 脱敏（强制）">
          <span class="form-tip">
            出境前强制 PII 脱敏（手机号 / 身份证 / 邮箱 / 银行卡 / IP），不可关闭；仅作用于外呼通道，
            不影响本轮原话与落库正文。
          </span>
        </el-form-item>

        <el-form-item v-if="probeResult" label="探测结果">
          <span class="form-tip">
            [{{ probeResult.status }}] {{ probeResult.detail }}
            <template v-if="probeResult.suggestion">（{{ probeResult.suggestion }}）</template>
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiRuntimeJev" lang="ts">
import { probeHealthItem } from '@/api/ai/health';
import type { HealthCheckItem } from '@/api/ai/health/types';
import { optionModelConfig } from '@/api/ai/model/config';
import type { ModelConfigVO } from '@/api/ai/model/config/types';
import { getJevConfig, updateJevConfig } from '@/api/ai/runtime';
import type { JevConfig } from '@/api/ai/runtime/types';
import modal from '@/plugins/modal';

/** 种子预置决策模型的模型标识（用于首屏默认选中） */
const SEED_MODEL_CODE = 'decision-model-preview';

const loading = ref(true);
const saving = ref(false);
const probing = ref(false);
const formRef = ref<ElFormInstance>();
const probeResult = ref<HealthCheckItem | null>(null);

/** 结构化决策表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const config = ref<JevConfig>({
  enabled: false,
  modelConfigId: null,
  timeoutSeconds: 1,
  mode: 'shadow',
  confidenceThreshold: 0.75,
  confidenceThresholdMedium: 0.65,
  confidenceThresholdLarge: 0.55,
  marginRatio: 2.0,
  failOpen: true,
  stateMaxChars: 4000,
  persistProbabilities: true,
  circuitBreakerEnabled: true,
  circuitBreakerThreshold: 3,
  circuitBreakerPauseSeconds: 300,
  stickyRoutingEnabled: false,
  stickyMinConfidence: 0.9,
  stickyMaxQueryLength: 12
});

/** 可选的决策模型（「模型配置」中类型为 decision 且启用中的记录） */
const decisionModels = ref<ModelConfigVO[]>([]);

/**
 * 运行模式：以「关闭 / 影子 / 接管」三档表达，内部由 enabled + mode 组合落地。
 * 「接管」为置信度闸门：达标即短路路由推理、直接交专家作答；未达标回落小Z 自行判定。
 */
const mode = computed<'off' | 'shadow' | 'gate'>({
  get: () => {
    if (!config.value.enabled) {
      return 'off';
    }
    return config.value.mode === 'gate' ? 'gate' : 'shadow';
  },
  set: (val) => {
    if (val === 'off') {
      config.value.enabled = false;
    } else {
      config.value.enabled = true;
      config.value.mode = val === 'gate' ? 'gate' : 'shadow';
    }
  }
});

/** 三档模式的中文提示（说明各档的用户可见行为） */
const modeTip = computed(() => {
  switch (mode.value) {
    case 'off':
      return '关闭：路由完全由小Z 自行判定，零外呼，与现状一致。';
    case 'shadow':
      return '影子：决策模型只留痕，不影响用户可见行为（启用后的默认档）。';
    default:
      return '接管：置信度闸门——判定达标且命中候选专家时，直接交该专家作答（任务片段取服务端模板），不再跑小Z 的路由推理；低置信 / 越界 / 判定不可用一律回落小Z 自行判定。代价：每轮多一次判定外呼（实测约 0.1~0.4 秒）。';
  }
});

/** 加载当前生效的结构化决策配置 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getJevConfig();
    if (res.data) {
      config.value = { ...res.data };
    }
  } finally {
    loading.value = false;
  }
};

/** 加载可选的决策模型（仅启用中的 decision 类型） */
const loadDecisionModels = async () => {
  const res = await optionModelConfig('decision');
  decisionModels.value = res.data ?? [];
};

/** 未选模型时，默认选中种子预置的决策模型（`decision-model-preview`），便于开箱即用 */
const applySeedDecisionModel = () => {
  if (config.value.modelConfigId) {
    return;
  }
  const seed = decisionModels.value.find((item) => item.modelCode === SEED_MODEL_CODE)
    ?? decisionModels.value.find((item) => item.configName === SEED_MODEL_CODE);
  if (seed) {
    config.value.modelConfigId = seed.id;
  }
};

/** 刷新：重读服务端配置，并在未选模型时重新套用种子默认值 */
const handleRefresh = async () => {
  await loadConfig();
  applySeedDecisionModel();
};

/** 保存结构化决策配置 */
const handleSave = async () => {
  if (config.value.enabled && !config.value.modelConfigId) {
    modal.msgWarning('已选择启用运行模式，请先选择一条决策模型（可在「模型配置」新增，类型选「决策模型」）');
    return;
  }
  saving.value = true;
  try {
    await updateJevConfig(config.value);
    modal.msgSuccess('保存成功，下一次编排即按新模式 / 阈值生效');
    await loadConfig();
  } finally {
    saving.value = false;
  }
};

/** 探测结构化决策连通性（真实调一次判定） */
const handleProbe = async () => {
  probing.value = true;
  try {
    const res = await probeHealthItem({ target: 'jev' });
    const item = res.data ?? null;
    probeResult.value = item;
    if (item?.status === 'PASS') {
      modal.msgSuccess(`探测通过：${item.detail}`);
    } else if (item) {
      modal.msgWarning(`探测结果 ${item.status}：${item.detail}`);
    }
  } finally {
    probing.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadConfig(), loadDecisionModels()]);
  applySeedDecisionModel();
});
</script>

<style scoped lang="scss">
.config-alert {
  margin-bottom: 16px;
}

.config-form {
  max-width: 860px;
  padding-top: 4px;
}

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.form-tip-block {
  margin-top: 6px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>