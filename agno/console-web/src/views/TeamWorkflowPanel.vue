<script setup lang="ts">
import { ref } from 'vue'
import ResourcePanel from '../components/ResourcePanel.vue'
import { listTeams, listWorkflows, type ColumnDef } from '../api'

const tab = ref('teams')

const teamColumns: ColumnDef[] = [
  { key: 'id', label: 'ID', width: 200, type: 'mono' },
  { key: 'name', label: '名称', width: 180 },
  { key: 'mode', label: '模式', width: 120, type: 'tag' },
  { key: 'description', label: '描述' },
]

const workflowColumns: ColumnDef[] = [
  { key: 'id', label: 'ID', width: 200, type: 'mono' },
  { key: 'name', label: '名称', width: 180 },
  { key: 'description', label: '描述' },
  { key: 'steps', label: '步骤', width: 220 },
]
</script>

<template>
  <el-tabs v-model="tab">
    <el-tab-pane label="团队" name="teams">
      <ResourcePanel
        title="团队"
        desc="已注册到 AgentOS 的多智能体团队"
        :loader="listTeams"
        :columns="teamColumns"
      />
    </el-tab-pane>
    <el-tab-pane label="工作流" name="workflows">
      <ResourcePanel
        title="工作流"
        desc="已注册到 AgentOS 的确定性流程"
        :loader="listWorkflows"
        :columns="workflowColumns"
      />
    </el-tab-pane>
  </el-tabs>
</template>
