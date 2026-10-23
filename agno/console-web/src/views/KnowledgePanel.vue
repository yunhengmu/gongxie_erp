<script setup lang="ts">
import ResourcePanel from '../components/ResourcePanel.vue'
import { listKnowledge, type ColumnDef } from '../api'

const columns: ColumnDef[] = [
  { key: 'name', label: '名称' },
  { key: 'type', label: '类型', width: 110 },
  { key: 'status', label: '状态', width: 110, type: 'tag' },
  { key: 'size', label: '大小', width: 100 },
  { key: 'updated_at', label: '更新时间', width: 180, type: 'time' },
]
</script>

<template>
  <ResourcePanel
    title="知识库"
    desc="已导入的知识内容，点击行查看元数据"
    :loader="listKnowledge"
    :columns="columns"
    empty-text="知识库未配置或暂无内容：需要在后端创建 Knowledge 实例并导入文档"
  />
</template>
