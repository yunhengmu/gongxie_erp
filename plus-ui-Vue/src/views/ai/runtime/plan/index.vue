<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>计划模式与任务清单</h3>
            <p>
              开启计划模式后，智能体先进入只读设计阶段：只允许只读工具与 plan_enter / plan_write / plan_exit，
              产出计划文件并经人工确认后才回到执行阶段。保存后写入运行期配置，下一次对话即按新策略重新装配生效。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button v-hasPermi="['ai:runtime:plan:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="220px" class="config-form">
        <el-form-item label="启用计划模式" prop="enabled">
          <template #label>
            <span class="label-tip">
              启用计划模式
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">开启后进入只读设计阶段，写类工具在计划阶段一律被拒。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="计划文件目录" prop="planFileDirectory">
          <template #label>
            <span class="label-tip">
              计划文件目录
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">相对智能体工作区的目录名，计划文件存放于此（默认 plans）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input v-model="form.planFileDirectory" placeholder="plans" style="width: 240px" />
        </el-form-item>
        <el-form-item label="启用任务清单" prop="taskListEnabled">
          <template #label>
            <span class="label-tip">
              启用任务清单
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">注册 todo_write 工具，让智能体把多步任务拆解为可跟踪的清单。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.taskListEnabled" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiRuntimePlan" lang="ts">
import { getPlanConfig, updatePlanConfig } from '@/api/ai/runtime';
import type { PlanConfig } from '@/api/ai/runtime/types';
import modal from '@/plugins/modal';

const loading = ref(true);
const saving = ref(false);
const formRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<PlanConfig>({
  enabled: false,
  planFileDirectory: 'plans',
  taskListEnabled: false
});

const rules = {
  planFileDirectory: [{ required: true, message: '计划文件目录不能为空', trigger: 'blur' }]
};

/** 加载当前生效配置 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getPlanConfig();
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
    await updatePlanConfig(form.value);
    modal.msgSuccess('保存成功，下一次对话即按新策略装配生效');
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
</style>