<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>编排预算</h3>
            <p>
              单轮编排（入口智能体每轮对话）的专家轮 Token 预算与墙钟预算。预算为「事后累计 + 触顶即停」：
              每次专家调用结束后累计，一旦超限即停止继续调用专家并如实降级说明。保存后写入运行期配置，下一次编排即按新预算生效。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button v-hasPermi="['ai:runtime:route-budget:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="220px" class="config-form">
        <el-form-item label="专家轮 Token 预算" prop="expertTokens">
          <template #label>
            <span class="label-tip">
              专家轮 Token 预算
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    单轮编排内全部专家调用的输入 + 输出 Token 累计上限；超过即停止继续调用专家。
                    0 表示不限。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.expertTokens" :min="0" :step="1000" controls-position="right" style="width: 220px" />
          <span class="form-tip">0 表示不限</span>
        </el-form-item>
        <el-form-item label="编排墙钟预算（秒）" prop="wallSeconds">
          <template #label>
            <span class="label-tip">
              编排墙钟预算（秒）
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">
                    单轮编排的墙钟耗时上限；HITL 暂停-恢复跨两次请求，恢复段按恢复段自身重新计时。
                    0 表示不限。
                  </div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.wallSeconds" :min="0" :step="10" controls-position="right" style="width: 220px" />
          <span class="form-tip">0 表示不限</span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiRuntimeRouteBudget" lang="ts">
import { getRouteBudgetConfig, updateRouteBudgetConfig } from '@/api/ai/runtime';
import type { RouteBudgetConfig } from '@/api/ai/runtime/types';
import modal from '@/plugins/modal';

const loading = ref(true);
const saving = ref(false);
const formRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<RouteBudgetConfig>({
  expertTokens: 60000,
  wallSeconds: 180
});

const rules = {
  expertTokens: [{ required: true, message: '专家轮 Token 预算不能为空', trigger: 'blur' }],
  wallSeconds: [{ required: true, message: '编排墙钟预算不能为空', trigger: 'blur' }]
};

/** 加载当前生效配置 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getRouteBudgetConfig();
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
    await updateRouteBudgetConfig(form.value);
    modal.msgSuccess('保存成功，下一次编排即按新预算生效');
    await loadConfig();
  } finally {
    saving.value = false;
  }
};

onMounted(loadConfig);
</script>

<style scoped lang="scss">
.config-form {
  max-width: 760px;
  padding-top: 4px;
}

.form-tip {
  margin-left: 10px;
  color: var(--app-text-muted);
  font-size: 12px;
}
</style>