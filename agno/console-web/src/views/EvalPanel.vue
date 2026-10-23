<script setup lang="ts">
import ResourcePanel from '../components/ResourcePanel.vue'
import { listEvalRuns, type ColumnDef } from '../api'

const columns: ColumnDef[] = [
  { key: 'name', label: '名称' },
  { key: 'eval_type', label: '类型', width: 140, type: 'tag' },
  { key: 'agent_id', label: 'Agent', width: 160 },
  { key: 'model_id', label: '模型', width: 180 },
  { key: 'created_at', label: '创建时间', width: 180, type: 'time' },
]
</script>

<template>
  <ResourcePanel
    title="评测"
    desc="评测运行记录，点击行查看 eval_data 详情"
    :loader="listEvalRuns"
    :columns="columns"
    empty-text="暂无评测记录：需要通过 API 或脚本发起 eval"
  />
</template>
