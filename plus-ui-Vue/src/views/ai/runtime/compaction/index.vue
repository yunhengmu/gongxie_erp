<template>
  <div class="p-2 app-container">
    <el-card shadow="hover" class="table-panel" v-loading="loading">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>上下文与压缩策略</h3>
            <p>
              配置超长对话的自动摘要阈值与压缩前落盘策略，以及大工具结果的落盘卸载阈值。保存后写入运行期配置，
              下一次对话即按新策略重新装配生效。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadConfig">刷新</el-button>
            <el-button v-hasPermi="['ai:runtime:compaction:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="220px" class="config-form">
        <el-divider content-position="left">上下文压缩</el-divider>
        <el-form-item label="启用上下文压缩" prop="enabled">
          <template #label>
            <span class="label-tip">
              启用上下文压缩
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">关闭后不做历史摘要，超长对话可能超出模型上下文窗口。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="触发压缩消息条数" prop="triggerMessages">
          <template #label>
            <span class="label-tip">
              触发压缩消息条数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">对话消息条数超过该值时触发压缩（框架默认 50）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.triggerMessages" :min="1" :max="10000" :step="5" />
        </el-form-item>
        <el-form-item label="触发压缩 Token 数" prop="triggerTokens">
          <template #label>
            <span class="label-tip">
              触发压缩 Token 数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">上下文 Token 估算超过该值时触发压缩（框架默认 80000）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.triggerTokens" :min="1000" :step="1000" />
        </el-form-item>
        <el-form-item label="保留尾部消息条数" prop="keepMessages">
          <template #label>
            <span class="label-tip">
              保留尾部消息条数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">压缩后原样保留最近 N 条消息，其余折叠为摘要（框架默认 20）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.keepMessages" :min="1" :max="10000" :step="5" />
        </el-form-item>
        <el-form-item label="压缩前落盘留痕" prop="flushBeforeCompact">
          <template #label>
            <span class="label-tip">
              压缩前落盘留痕
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">压缩前先把完整历史写入工作区留痕，便于事后追溯。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.flushBeforeCompact" />
        </el-form-item>
        <el-form-item label="压缩前卸载为 jsonl" prop="offloadBeforeCompact">
          <template #label>
            <span class="label-tip">
              压缩前卸载为 jsonl
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">开启后历史正文卸载为工作区下的 *.log.jsonl 文件，仅保留摘要与指针。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.offloadBeforeCompact" />
        </el-form-item>

        <el-divider content-position="left">大工具结果卸载</el-divider>
        <el-form-item label="启用大工具结果卸载" prop="toolResultEvictionEnabled">
          <template #label>
            <span class="label-tip">
              启用大工具结果卸载
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">超大工具结果落盘，仅回填首尾预览，避免单次结果吃满上下文。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-switch v-model="form.toolResultEvictionEnabled" />
        </el-form-item>
        <el-form-item label="卸载阈值（字符）" prop="toolResultEvictionMaxChars">
          <template #label>
            <span class="label-tip">
              卸载阈值（字符）
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">工具结果字符数超过该值时触发卸载（框架默认 80000）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.toolResultEvictionMaxChars" :min="1000" :step="1000" />
        </el-form-item>
        <el-form-item label="预览字符数" prop="toolResultEvictionPreviewChars">
          <template #label>
            <span class="label-tip">
              预览字符数
              <el-tooltip placement="top">
                <template #content>
                  <div class="tip-content">卸载后保留的首尾各一份预览长度（框架默认 2000）。</div>
                </template>
                <el-icon class="label-tip__icon" tabindex="0"><InfoFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <el-input-number v-model="form.toolResultEvictionPreviewChars" :min="100" :step="100" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup name="AiRuntimeCompaction" lang="ts">
import { getCompactionConfig, updateCompactionConfig } from '@/api/ai/runtime';
import type { CompactionConfig } from '@/api/ai/runtime/types';
import modal from '@/plugins/modal';

const loading = ref(true);
const saving = ref(false);
const formRef = ref<ElFormInstance>();

/** 表单默认值（仅用于首屏占位，加载后以服务端解析结果为准） */
const form = ref<CompactionConfig>({
  enabled: true,
  triggerMessages: 50,
  triggerTokens: 80000,
  keepMessages: 20,
  flushBeforeCompact: true,
  offloadBeforeCompact: false,
  toolResultEvictionEnabled: true,
  toolResultEvictionMaxChars: 80000,
  toolResultEvictionPreviewChars: 2000
});

const rules = {
  triggerMessages: [{ required: true, message: '触发压缩消息条数不能为空', trigger: 'blur' }],
  triggerTokens: [{ required: true, message: '触发压缩 Token 数不能为空', trigger: 'blur' }],
  keepMessages: [{ required: true, message: '保留尾部消息条数不能为空', trigger: 'blur' }],
  toolResultEvictionMaxChars: [{ required: true, message: '卸载阈值不能为空', trigger: 'blur' }],
  toolResultEvictionPreviewChars: [{ required: true, message: '预览字符数不能为空', trigger: 'blur' }]
};

/** 加载当前生效策略 */
const loadConfig = async () => {
  loading.value = true;
  try {
    const res = await getCompactionConfig();
    if (res.data) {
      form.value = res.data;
    }
  } finally {
    loading.value = false;
  }
};

/** 保存策略 */
const handleSave = async () => {
  await formRef.value?.validate();
  saving.value = true;
  try {
    await updateCompactionConfig(form.value);
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