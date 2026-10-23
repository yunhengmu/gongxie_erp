<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login } from '../api'

const emit = defineEmits<{ success: [] }>()

const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await login(form.username.trim(), form.password)
    emit('success')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : String(err))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <el-card class="login-card" shadow="never">
      <div class="brand">
        <span class="dot" />
        <div>
          <div class="brand-title">Agno Console</div>
          <div class="brand-sub">请登录后使用</div>
        </div>
      </div>

      <el-form style="margin-top: 24px" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" size="large" placeholder="用户名" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            show-password
            size="large"
            placeholder="密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="hint" style="margin-top: 14px">
        默认管理员：admin / admin123，可用环境变量 CONSOLE_ADMIN_PASSWORD 覆盖
      </div>
    </el-card>
  </div>
</template>
