<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>安全合规配置</h3>
            <p>
              对用户输入、模型输出与渠道出站内容做敏感词与 PII 检测，命中后按动作「只记录 / 脱敏 / 阻断」处理。
              保存后写入运行期配置，下一次对话 / 编排即按新策略生效。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button v-hasPermi="['ai:runtime:security:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="security-alert"
        type="info"
        :closable="false"
        show-icon
        title="默认关闭；默认「只记录」（灰度观察），PII 脱敏默认开启。开启阻断前建议先以「只记录」跑一段时间，评估误伤后再生效。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="220px" class="config-form">
        <el-form-item label="启用安全合规" prop="enabled">
          <el-switch v-model="form.enabled" />
          <span class="form-tip">关闭后所有场景的检测与拦截全部停用</span>
        </el-form-item>

        <el-form-item label="检测引擎" prop="engine">
          <el-select v-model="form.engine" style="width: 260px">
            <el-option label="本地词库（内置敏感词 / 正则）" value="local" />
            <el-option label="外部 API（调用第三方内容安全接口）" value="external" />
          </el-select>
        </el-form-item>

        <el-form-item label="命中动作" prop="action">
          <el-select v-model="form.action" style="width: 260px">
            <el-option label="只记录（命中不拦截）" value="log" />
            <el-option label="脱敏放行（替换命中片段后放行）" value="mask" />
            <el-option label="阻断（直接拒绝并给出提示）" value="block" />
          </el-select>
        </el-form-item>

        <el-form-item label="用户输入检测" prop="inputCheck">
          <el-switch v-model="form.inputCheck" />
        </el-form-item>
        <el-form-item label="模型输出检测" prop="outputCheck">
          <el-switch v-model="form.outputCheck" />
        </el-form-item>
        <el-form-item label="渠道出站检测" prop="channelOutCheck">
          <el-switch v-model="form.channelOutCheck" />
          <span class="form-tip">回推到飞书 / 企业微信 / GitHub / 钉钉等渠道前先做一次检测</span>
        </el-form-item>

        <el-form-item label="工具结果审核" prop="toolResultMask">
          <template #label>
            <span class="label-tip">
              工具结果审核
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">落库留痕与前端展示，框架内部回填模型不可见。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.toolResultMask" />
        </el-form-item>
        <el-form-item label="知识库入库审核" prop="kbIngestCheck">
          <template #label>
            <span class="label-tip">
              知识库入库审核
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">切片送向量库前审核，命中阻断则拒绝入库。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.kbIngestCheck" />
        </el-form-item>
        <el-form-item label="MCP 出口审核" prop="mcpOutCheck">
          <template #label>
            <span class="label-tip">
              MCP 出口审核
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">对外暴露给第三方 AI 的只读工具入参 / 出参。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.mcpOutCheck" />
        </el-form-item>
        <el-form-item label="提示词注入防护" prop="promptHardening">
          <template #label>
            <span class="label-tip">
              提示词注入防护
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">system prompt 加固 + 外部内容 &lt;untrusted&gt; 边界标记。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.promptHardening" />
        </el-form-item>

        <el-form-item label="PII 脱敏" prop="maskPii">
          <template #label>
            <span class="label-tip">
              PII 脱敏
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">对手机号 / 身份证 / 邮箱 / 银行卡 / IP 做掩码脱敏。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.maskPii" />
          <span class="form-tip">手机号 / 身份证 / 邮箱 / 银行卡 / IP</span>
        </el-form-item>

        <el-form-item label="流式回退字符数" prop="holdBackChars">
          <template #label>
            <span class="label-tip">
              流式回退字符数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">流式输出先缓冲 N 个字符再判定，避免命中片段被提前放出；0 表示不缓冲。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.holdBackChars" :min="0" :step="10" controls-position="right" style="width: 220px" />
          <span class="form-tip">0 表示不缓冲</span>
        </el-form-item>
        <el-form-item label="命中留证片段字符数" prop="excerptChars">
          <el-input-number v-model="form.excerptChars" :min="10" :step="10" controls-position="right" style="width: 220px" />
          <span class="form-tip">安全事件中保留的命中原文长度</span>
        </el-form-item>

        <template v-if="form.engine === 'external'">
          <el-form-item label="外部引擎接口地址" prop="providerEndpoint">
            <el-input v-model="form.providerEndpoint" placeholder="https://example.com/api/moderate" style="width: 420px" />
          </el-form-item>
          <el-form-item label="外部引擎接口密钥" prop="providerApiKey">
            <el-input
              v-model="form.providerApiKey"
              type="password"
              show-password
              placeholder="请输入接口密钥"
              style="width: 420px"
            />
          </el-form-item>
        </template>
      </el-form>
    </el-card>

    <el-card shadow="hover" class="table-panel jev-panel" v-loading="jevLoading">
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
            <el-button v-hasPermi="['ai:runtime:security:query']" icon="Refresh" @click="loadJevConfig">刷新</el-button>
            <el-button
              v-hasPermi="['ai:health:probe']"
              icon="Connection"
              :loading="jevProbing"
              @click="handleProbeJev"
            >
              连通性探测
            </el-button>
            <el-button
              v-hasPermi="['ai:runtime:security:edit']"
              type="primary"
              icon="Check"
              :loading="jevSaving"
              @click="handleJevSave"
            >
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="security-alert"
        type="warning"
        :closable="false"
        show-icon
        title="本分组与「安全合规配置」同页，复用其查询 / 编辑权限。出境前强制 PII 脱敏，不可关闭。"
      />

      <el-form ref="jevFormRef" :model="jev" label-width="220px" class="config-form">
        <el-form-item label="运行模式">
          <el-radio-group v-model="jevMode">
            <el-radio-button value="off">关闭</el-radio-button>
            <el-radio-button value="shadow">影子</el-radio-button>
            <el-radio-button value="gate">接管</el-radio-button>
          </el-radio-group>
          <div class="form-tip-block">{{ jevModeTip }}</div>
        </el-form-item>

        <el-form-item label="服务地址 base-url">
          <el-input
            v-model="jev.baseUrl"
            placeholder="https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode"
            style="width: 460px"
          />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input v-model="jev.model" placeholder="decision-model-preview" style="width: 320px" />
        </el-form-item>
        <el-form-item label="超时（秒）">
          <el-input-number v-model="jev.timeoutSeconds" :min="1" :max="120" controls-position="right" style="width: 220px" />
        </el-form-item>
        <el-form-item label="置信度阈值">
          <el-input-number
            v-model="jev.confidenceThreshold"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="2"
            controls-position="right"
            style="width: 220px"
          />
          <span class="form-tip">0~1，仅「接管」档使用，建议用影子期数据校准</span>
        </el-form-item>
        <el-form-item label="状态持久化">
          <el-switch v-model="jev.persistProbabilities" />
          <span class="form-tip">落库概率分布（影子期校准用），接管稳定后可关闭以省存储</span>
        </el-form-item>
        <el-form-item label="容错回落">
          <el-switch v-model="jev.failOpen" />
          <span class="form-tip">判定不可用时回落工具决策（恒定回落，不新增失败面）</span>
        </el-form-item>
        <el-form-item label="state 字符上限">
          <el-input-number v-model="jev.stateMaxChars" :min="100" :max="65536" :step="100" controls-position="right" style="width: 220px" />
          <span class="form-tip">外呼文本前置保护，防止超长被拒</span>
        </el-form-item>

        <el-form-item label="访问密钥 api-key">
          <el-input
            v-model="jev.apiKey"
            type="password"
            show-password
            placeholder="留空表示不修改已保存的密钥"
            style="width: 420px"
          />
          <span class="form-tip">当前状态：{{ jev.apiKeyConfigured ? '已配置' : '未配置' }}；留空表示不修改已保存的密钥</span>
        </el-form-item>

        <el-form-item label="PII 脱敏（强制）">
          <span class="form-tip">
            出境前强制 PII 脱敏（手机号 / 身份证 / 邮箱 / 银行卡 / IP），不可关闭；仅作用于外呼通道，
            不影响本轮原话与落库正文。
          </span>
        </el-form-item>

        <el-form-item v-if="jevProbeResult" label="探测结果">
          <span class="form-tip">
            [{{ jevProbeResult.status }}] {{ jevProbeResult.detail }}
            <template v-if="jevProbeResult.suggestion">（{{ jevProbeResult.suggestion }}）</template>
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiRuntimeSecurity" lang="ts">
import { probeHealthItem } from '@/api/ai/health';
import type { HealthCheckItem } from '@/api/ai/health/types';
import { getJevConfig, getSecurityConfig, updateJevConfig, updateSecurityConfig } from '@/api/ai/runtime';
import type { JevConfig, SecurityConfig } from '@/api/ai/runtime/types';
import modal from '@/plugins/modal';

const loading = ref(true);
const saving = ref(false);
const formRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<SecurityConfig>({
  enabled: false,
  engine: 'local',
  action: 'log',
  inputCheck: true,
  outputCheck: true,
  channelOutCheck: false,
  toolResultMask: true,
  kbIngestCheck: true,
  mcpOutCheck: true,
  promptHardening: true,
  maskPii: true,
  holdBackChars: 20,
  excerptChars: 120,
  providerEndpoint: '',
  providerApiKey: ''
});

const rules = {
  engine: [{ required: true, message: '请选择检测引擎', trigger: 'change' }],
  action: [{ required: true, message: '请选择命中动作', trigger: 'change' }]
};

/** 加载当前生效配置 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getSecurityConfig();
    if (res.data) {
      form.value = res.data;
    }
  } finally {
    loading.value = false;
  }
};

/** 保存配置 */
const handleSave = async () => {
  await formRef.value?.validate();
  saving.value = true;
  try {
    await updateSecurityConfig(form.value);
    modal.msgSuccess('保存成功，下一次对话 / 编排即按新策略生效');
    await loadConfig();
  } finally {
    saving.value = false;
  }
};

// ---------------------------------------------------------------- 结构化决策路由

const jevLoading = ref(true);
const jevSaving = ref(false);
const jevProbing = ref(false);
const jevFormRef = ref<ElFormInstance>();
const jevProbeResult = ref<HealthCheckItem | null>(null);

/** 结构化决策表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const jev = ref<JevConfig>({
  enabled: false,
  baseUrl: '',
  model: 'decision-model-preview',
  timeoutSeconds: 5,
  mode: 'shadow',
  confidenceThreshold: 0.75,
  failOpen: true,
  stateMaxChars: 4000,
  persistProbabilities: true,
  apiKeyConfigured: false,
  apiKey: ''
});

/**
 * 运行模式：以「关闭 / 影子 / 接管」三档表达，内部由 enabled + mode 组合落地。
 * 「接管」为置信度闸门：达标即短路路由推理、直接交专家作答；未达标回落小Z 自行判定。
 */
const jevMode = computed<'off' | 'shadow' | 'gate'>({
  get: () => {
    if (!jev.value.enabled) {
      return 'off';
    }
    return jev.value.mode === 'gate' ? 'gate' : 'shadow';
  },
  set: (val) => {
    if (val === 'off') {
      jev.value.enabled = false;
    } else {
      jev.value.enabled = true;
      jev.value.mode = val === 'gate' ? 'gate' : 'shadow';
    }
  }
});

/** 三档模式的中文提示（说明各档的用户可见行为） */
const jevModeTip = computed(() => {
  switch (jevMode.value) {
    case 'off':
      return '关闭：路由完全由小Z 自行判定，零外呼，与现状一致。';
    case 'shadow':
      return '影子：决策模型只留痕，不影响用户可见行为（启用后的默认档）。';
    default:
      return '接管：置信度闸门——判定达标且命中候选专家时，直接交该专家作答（任务片段取服务端模板），不再跑小Z 的路由推理；低置信 / 越界 / 判定不可用一律回落小Z 自行判定。代价：每轮多一次判定外呼（实测约 0.1~0.4 秒）。';
  }
});

/** 加载当前生效的结构化决策配置 */
const loadJevConfig = async () => {
  jevLoading.value = true;
  try {
    const res = await getJevConfig();
    if (res.data) {
      // 服务端不回显密钥，读取后密钥输入框始终置空
      jev.value = { ...res.data, apiKey: '' };
    }
  } finally {
    jevLoading.value = false;
  }
};

/** 保存结构化决策配置（独立保存；密钥留空表示不修改已保存密钥） */
const handleJevSave = async () => {
  jevSaving.value = true;
  try {
    // apiKeyConfigured 是服务端回显的只读状态（后端 Bo 无该字段），提交前剔除，保持请求体即配置体
    const payload = { ...jev.value };
    delete payload.apiKeyConfigured;
    await updateJevConfig(payload);
    modal.msgSuccess('保存成功，下一次编排即按新模式 / 阈值生效');
    await loadJevConfig();
  } finally {
    jevSaving.value = false;
  }
};

/** 探测结构化决策连通性（真实调一次判定） */
const handleProbeJev = async () => {
  jevProbing.value = true;
  try {
    const res = await probeHealthItem({ target: 'jev' });
    const item = res.data ?? null;
    jevProbeResult.value = item;
    if (item?.status === 'PASS') {
      modal.msgSuccess(`探测通过：${item.detail}`);
    } else if (item) {
      modal.msgWarning(`探测结果 ${item.status}：${item.detail}`);
    }
  } finally {
    jevProbing.value = false;
  }
};

onMounted(async () => {
  await Promise.all([loadConfig(), loadJevConfig()]);
});
</script>

<style scoped lang="scss">
.security-alert {
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

.jev-panel {
  margin-top: 16px;
}

.form-tip-block {
  margin-top: 6px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>