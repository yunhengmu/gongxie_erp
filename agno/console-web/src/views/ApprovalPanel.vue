<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import ResourcePanel from '../components/ResourcePanel.vue'
import { deleteApproval, listApprovals, resolveApproval, type ColumnDef } from '../api'

const columns: ColumnDef[] = [
  { key: 'status', label: '状态', width: 110, type: 'tag' },
  { key: 'tool_name', label: '工具', width: 160 },
  { key: 'approval_type', label: '审批类型', width: 130 },
  { key: 'source_type', label: '来源', width: 110 },
  { key: 'agent_id', label: 'Agent', width: 160 },
  { key: 'created_at', label: '创建时间', width: 180, type: 'time' },
]

const actions = [
  {
    label: '批准',
    type: 'success' as const,
    handler: async (row: any) => {
      await resolveApproval(row.id, 'approved', 'local-admin')
    },
  },
  {
    label: '拒绝',
    type: 'danger' as const,
    handler: async (row: any) => {
      await resolveApproval(row.id, 'rejected', 'local-admin')
    },
  },
  {
    label: '删除',
    type: 'info' as const,
    handler: async (row: any) => {
      await ElMessageBox.confirm('确认删除这条审批记录？', '删除审批', { type: 'warning' })
      await deleteApproval(row.id)
    },
  },
]
</script>

<template>
  <ResourcePanel
    title="审批"
    desc="需要人工确认的工具调用（HITL），可直接批准或拒绝"
    :loader="listApprovals"
    :columns="columns"
    :actions="actions"
    empty-text="暂无审批：需要带 requires_confirmation 的工具被真实运行触发"
  />
</template>
