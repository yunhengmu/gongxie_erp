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
  </div>
</template>

<script setup name="AiRuntimeSecurity" lang="ts">
import { getSecurityConfig, updateSecurityConfig } from '@/api/ai/runtime';
import type { SecurityConfig } from '@/api/ai/runtime/types';
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

onMounted(loadConfig);
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
</style>