<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getConfig, getProviders, saveConfig, type AgentConfig, type ProviderSpec } from '../api'

const emit = defineEmits<{ saved: [] }>()

const form = reactive<AgentConfig>({
  agent_id: 'assistant',
  name: 'assistant',
  provider: 'openai',
  model_id: 'gpt-4o-mini',
  base_url: '',
  host: '',
  temperature: 0.7,
  max_tokens: null,
  instructions: '',
  markdown: true,
  api_key_set: false,
})

const apiKey = ref('')
const maxTokens = ref<number | undefined>(undefined)
const loading = ref(false)
const saving = ref(false)
const providers = ref<ProviderSpec[]>([])

const currentProvider = computed(() => providers.value.find((item) => item.key === form.provider))
const currentFields = computed(() => currentProvider.value?.fields || ['model_id', 'api_key'])

async function load() {
  loading.value = true
  try {
    const config = await getConfig()
    Object.assign(form, config)
    maxTokens.value = config.max_tokens ?? undefined
    apiKey.value = ''
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const payload: Partial<AgentConfig> = {
      agent_id: form.agent_id,
      name: form.name,
      provider: form.provider,
      model_id: form.model_id,
      base_url: form.base_url,
      host: form.host,
      temperature: form.temperature,
      max_tokens: maxTokens.value ?? null,
      instructions: form.instructions,
      markdown: form.markdown,
    }
    if (apiKey.value.trim()) {
      payload.api_key = apiKey.value.trim()
    }
    const result = await saveConfig(payload)
    ElMessage.success(`已保存，当前模型：${result.agent.model}`)
    emit('saved')
    await load()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    providers.value = (await getProviders()).data || []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
  await load()
})
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <div class="card-title">模型与智能体参数</div>
    <div class="card-desc">保存后立即重建 Agent，下一次对话生效，无需重启服务。</div>

    <el-form :model="form" label-width="120px" label-position="left">
      <el-form-item label="显示名称">
        <el-input v-model="form.name" placeholder="assistant" />
      </el-form-item>

      <el-form-item label="模型来源">
        <el-select v-model="form.provider" style="width: 260px">
          <el-option
            v-for="item in providers"
            :key="item.key"
            :label="item.available ? item.label : item.label + '（不可用）'"
            :value="item.key"
            :disabled="!item.available"
          />
        </el-select>
        <span v-if="currentProvider?.env" class="hint" style="margin-left: 10px">
          未填 Key 时读取 {{ currentProvider.env }}
        </span>
      </el-form-item>

      <el-form-item label="模型 ID">
        <el-input v-model="form.model_id" placeholder="gpt-4o-mini" />
      </el-form-item>

      <el-form-item v-if="currentFields.includes('base_url')" label="Base URL">
        <el-input v-model="form.base_url" placeholder="https://your-gateway.example.com/v1" />
      </el-form-item>

      <el-form-item v-if="currentFields.includes('host')" label="Host">
        <el-input v-model="form.host" placeholder="http://localhost:11434" />
      </el-form-item>

      <el-form-item v-if="currentFields.includes('api_key')" label="API Key">
        <el-input
          v-model="apiKey"
          type="password"
          show-password
          :placeholder="form.api_key_set ? '已设置，留空保持不变' : '未设置，请填写'"
        />
      </el-form-item>

      <el-form-item label="Temperature">
        <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" show-input style="max-width: 420px" />
      </el-form-item>

      <el-form-item label="Max Tokens">
        <el-input-number v-model="maxTokens" :min="1" :step="256" controls-position="right" style="width: 200px" />
        <span class="hint" style="margin-left: 12px">留空表示使用模型默认值</span>
      </el-form-item>

      <el-form-item label="Instructions">
        <el-input v-model="form.instructions" type="textarea" :rows="8" placeholder="定义智能体的角色与行为约束" />
      </el-form-item>

      <el-form-item label="Markdown">
        <el-switch v-model="form.markdown" />
      </el-form-item>

      <el-form-item>
        <div class="form-footer">
          <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
          <el-button @click="load">重新读取</el-button>
          <span class="hint">API Key 以明文保存在本地 console.config.json，仅建议本机使用</span>
        </div>
      </el-form-item>
    </el-form>
  </el-card>
</template>
