<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResourcePanel from '../components/ResourcePanel.vue'
import {
  createLearning,
  createMemory,
  deleteLearning,
  deleteMemory,
  listLearnings,
  listMemories,
  type ColumnDef,
} from '../api'

const tab = ref('memories')
const memoryPanel = ref<any>(null)
const learningPanel = ref<any>(null)

const memoryColumns: ColumnDef[] = [
  { key: 'memory', label: '内容' },
  { key: 'topics', label: '主题', width: 160 },
  { key: 'user_id', label: '用户', width: 140 },
  { key: 'updated_at', label: '更新时间', width: 180, type: 'time' },
]

const learningColumns: ColumnDef[] = [
  { key: 'learning_type', label: '类型', width: 130, type: 'tag' },
  { key: 'namespace', label: '命名空间', width: 150, type: 'mono' },
  { key: 'content', label: '内容' },
  { key: 'created_at', label: '创建时间', width: 180, type: 'time' },
]

const memoryActions = [
  {
    label: '删除',
    type: 'danger' as const,
    handler: async (row: any) => {
      await ElMessageBox.confirm('确认删除这条记忆？', '删除记忆', { type: 'warning' })
      await deleteMemory(row.memory_id || row.id)
    },
  },
]

const learningActions = [
  {
    label: '删除',
    type: 'danger' as const,
    handler: async (row: any) => {
      await ElMessageBox.confirm('确认删除这条学习记录？', '删除学习记录', { type: 'warning' })
      await deleteLearning(row.learning_id || row.id)
    },
  },
]

const memoryDialog = ref(false)
const memoryForm = reactive({ memory: '', user_id: '', topics: '' })
const learningDialog = ref(false)
const learningForm = reactive({ learning_type: 'user_profile', namespace: 'user', user_id: '', content: '' })

function openMemory() {
  memoryForm.memory = ''
  memoryForm.user_id = ''
  memoryForm.topics = ''
  memoryDialog.value = true
}

async function submitMemory() {
  if (!memoryForm.memory.trim()) {
    ElMessage.warning('请填写记忆内容')
    return
  }
  try {
    await createMemory({
      memory: memoryForm.memory.trim(),
      user_id: memoryForm.user_id.trim() || undefined,
      topics: memoryForm.topics
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean),
    })
    ElMessage.success('记忆已创建')
    memoryDialog.value = false
    memoryPanel.value?.reload()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}

function openLearning() {
  learningForm.learning_type = 'user_profile'
  learningForm.namespace = 'user'
  learningForm.user_id = ''
  learningForm.content = ''
  learningDialog.value = true
}

async function submitLearning() {
  if (!learningForm.content.trim()) {
    ElMessage.warning('请填写内容 JSON')
    return
  }
  let content: Record<string, any>
  try {
    content = JSON.parse(learningForm.content)
  } catch {
    ElMessage.error('内容必须是合法 JSON 对象')
    return
  }
  try {
    await createLearning({
      learning_type: learningForm.learning_type.trim() || 'user_profile',
      content,
      namespace: learningForm.namespace.trim() || 'user',
      user_id: learningForm.user_id.trim() || undefined,
    })
    ElMessage.success('学习记录已创建')
    learningDialog.value = false
    learningPanel.value?.reload()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  }
}
</script>

<template>
  <el-tabs v-model="tab">
    <el-tab-pane label="用户记忆" name="memories">
      <ResourcePanel
        ref="memoryPanel"
        title="用户记忆"
        desc="跨会话保留的用户事实与偏好"
        :loader="listMemories"
        :columns="memoryColumns"
        :actions="memoryActions"
        empty-text="暂无记忆，可手动新增，或在真实对话中由 Agent 写入"
      >
        <template #toolbar>
          <el-button type="primary" size="small" @click="openMemory">新增记忆</el-button>
        </template>
      </ResourcePanel>
    </el-tab-pane>

    <el-tab-pane label="学习记录" name="learnings">
      <ResourcePanel
        ref="learningPanel"
        title="学习记录"
        desc="Agent 在运行中积累的学习条目"
        :loader="listLearnings"
        :columns="learningColumns"
        :actions="learningActions"
        empty-text="暂无学习记录，可手动新增，或开启 learning 后由对话产生"
      >
        <template #toolbar>
          <el-button type="primary" size="small" @click="openLearning">新增学习记录</el-button>
        </template>
      </ResourcePanel>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="memoryDialog" title="新增用户记忆" width="520px">
    <el-form :model="memoryForm" label-width="90px">
      <el-form-item label="内容">
        <el-input v-model="memoryForm.memory" type="textarea" :rows="4" placeholder="例如：用户偏好简洁回答" />
      </el-form-item>
      <el-form-item label="用户 ID">
        <el-input v-model="memoryForm.user_id" placeholder="留空则为全局" />
      </el-form-item>
      <el-form-item label="主题">
        <el-input v-model="memoryForm.topics" placeholder="逗号分隔，可选" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="memoryDialog = false">取消</el-button>
      <el-button type="primary" @click="submitMemory">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="learningDialog" title="新增学习记录" width="560px">
    <el-form :model="learningForm" label-width="100px">
      <el-form-item label="类型">
        <el-input v-model="learningForm.learning_type" placeholder="user_profile / entity_memory" />
      </el-form-item>
      <el-form-item label="命名空间">
        <el-input v-model="learningForm.namespace" placeholder="user / global" />
      </el-form-item>
      <el-form-item label="用户 ID">
        <el-input v-model="learningForm.user_id" placeholder="留空则不绑定用户" />
      </el-form-item>
      <el-form-item label="内容 JSON">
        <el-input v-model="learningForm.content" type="textarea" :rows="6" placeholder='{"summary": "示例"}' />
      </el-form-item>
    </el-form>
    <div class="dialog-hint">内容必须是合法 JSON 对象，例如 {"summary":"偏好中文回答"}。</div>
    <template #footer>
      <el-button @click="learningDialog = false">取消</el-button>
      <el-button type="primary" @click="submitLearning">创建</el-button>
    </template>
  </el-dialog>
</template>
