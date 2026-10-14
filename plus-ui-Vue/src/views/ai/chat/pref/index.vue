<template>
  <div class="p-2 app-container">
    <el-card v-loading="loading" shadow="hover" class="form-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>对话偏好</h3>
            <p>
              设置你个人的时区与语言，作用于与小Z 的所有对话及专家任务，<b>优先于平台默认</b>。
              留空表示该项不设置、沿用平台默认；保存后下一轮对话即生效。
            </p>
          </div>
          <div class="toolbar-actions">
            <el-button icon="Refresh" @click="loadPref">刷新</el-button>
            <el-button v-hasPermi="['ai:chat:pref:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">
              保存
            </el-button>
          </div>
        </div>
      </template>

      <el-form ref="prefFormRef" :model="form" label-width="130px" class="pref-form">
        <el-form-item label="时区">
          <el-select v-model="form.timezone" filterable allow-create clearable placeholder="留空则用平台默认时区" style="width: 320px">
            <el-option v-for="item in TIMEZONE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="语言">
          <el-select v-model="form.locale" filterable allow-create clearable placeholder="留空则用平台默认语言" style="width: 320px">
            <el-option v-for="item in LOCALE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['ai:chat:pref:edit']" type="primary" icon="Check" :loading="saving" @click="handleSave">保 存</el-button>
          <el-button @click="loadPref">重 置</el-button>
          <el-button v-hasPermi="['ai:chat:pref:edit']" type="danger" plain icon="Delete" :loading="clearing" @click="handleClear">
            清空我的设置
          </el-button>
        </el-form-item>
      </el-form>

      <div class="effective-shell">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="平台默认">
            {{ pref.platformTimezone || '-' }} / {{ pref.platformLocale || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="我已设置">
            {{ pref.timezone || '未设置' }} / {{ pref.locale || '未设置' }}
          </el-descriptions-item>
          <el-descriptions-item label="实际生效">
            {{ pref.effectiveTimezone || '-' }} / {{ pref.effectiveLocale || '-' }}
          </el-descriptions-item>
        </el-descriptions>
        <p class="effective-tip">「实际生效」＝ 我已设置优先，未设置则回落平台默认。格式：时区 / 语言。</p>
      </div>
    </el-card>
  </div>
</template>

<script setup name="AiChatPref" lang="ts">
import { clearChatPref, getChatPref, saveChatPref } from '@/api/ai/chat/pref';
import type { ChatPref } from '@/api/ai/chat/pref';
import { useLoading } from '@/hooks/async/useLoading';
import modal from '@/plugins/modal';

const TIMEZONE_OPTIONS = [
  { label: 'Asia/Shanghai（北京时间）', value: 'Asia/Shanghai' },
  { label: 'Asia/Hong_Kong（香港）', value: 'Asia/Hong_Kong' },
  { label: 'Asia/Tokyo（东京）', value: 'Asia/Tokyo' },
  { label: 'Asia/Singapore（新加坡）', value: 'Asia/Singapore' },
  { label: 'Asia/Kolkata（印度）', value: 'Asia/Kolkata' },
  { label: 'Asia/Dubai（迪拜）', value: 'Asia/Dubai' },
  { label: 'Europe/London（伦敦）', value: 'Europe/London' },
  { label: 'Europe/Paris（巴黎）', value: 'Europe/Paris' },
  { label: 'Europe/Moscow（莫斯科）', value: 'Europe/Moscow' },
  { label: 'America/New_York（纽约）', value: 'America/New_York' },
  { label: 'America/Chicago（芝加哥）', value: 'America/Chicago' },
  { label: 'America/Los_Angeles（洛杉矶）', value: 'America/Los_Angeles' },
  { label: 'Australia/Sydney（悉尼）', value: 'Australia/Sydney' },
  { label: 'UTC（协调世界时）', value: 'UTC' }
];

const LOCALE_OPTIONS = [
  { label: 'zh-CN（简体中文）', value: 'zh-CN' },
  { label: 'zh-TW（繁體中文）', value: 'zh-TW' },
  { label: 'en-US（English, US）', value: 'en-US' },
  { label: 'en-GB（English, UK）', value: 'en-GB' },
  { label: 'ja-JP（日本語）', value: 'ja-JP' },
  { label: 'ko-KR（한국어）', value: 'ko-KR' },
  { label: 'fr-FR（Français）', value: 'fr-FR' },
  { label: 'de-DE（Deutsch）', value: 'de-DE' },
  { label: 'es-ES（Español）', value: 'es-ES' },
  { label: 'ru-RU（Русский）', value: 'ru-RU' }
];

const { loading, withLoading } = useLoading(true);
const saving = ref(false);
const clearing = ref(false);
const prefFormRef = ref<ElFormInstance>();

/** 服务端回带的三段值（平台默认 / 我已设置 / 实际生效） */
const pref = ref<ChatPref>({});

/** 编辑中的表单（仅时区 / 语言两项，空白表示不设置） */
const form = ref<ChatPref>({
  timezone: undefined,
  locale: undefined
});

/** 加载当前偏好（同时刷新三段值展示） */
const loadPref = async () => {
  await withLoading(async () => {
    const res = await getChatPref();
    const data = res.data || {};
    pref.value = data;
    form.value = { timezone: data.timezone || undefined, locale: data.locale || undefined };
  });
};

/** 保存偏好（整份提交，留空项表示不设置） */
const handleSave = async () => {
  saving.value = true;
  try {
    await saveChatPref({ timezone: form.value.timezone, locale: form.value.locale });
    modal.msgSuccess('保存成功，下一轮对话即生效');
    await loadPref();
  } finally {
    saving.value = false;
  }
};

/** 清空人级设置，使两项均回落平台默认 */
const handleClear = async () => {
  try {
    await modal.confirm('确定清空你的时区与语言设置吗？清空后两项均回落平台默认。');
  } catch {
    return;
  }
  clearing.value = true;
  try {
    await clearChatPref();
    modal.msgSuccess('已清空，回落平台默认');
    await loadPref();
  } finally {
    clearing.value = false;
  }
};

onMounted(() => {
  loadPref();
});
</script>

<style scoped lang="scss">
.form-panel {
  max-width: 880px;
}

.pref-form {
  max-width: 620px;
  padding-top: 4px;
}

.effective-shell {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.effective-tip {
  margin: 8px 0 0;
  color: var(--app-text-muted);
  font-size: 12px;
}
</style>
