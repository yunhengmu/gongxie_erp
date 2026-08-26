<script lang="ts" setup>
import AiChat from '../index/index.vue';

defineOptions({ name: 'AiChatStandalone' });
</script>

<template>
  <!-- 独立弹出页面：脱离后台布局，全屏展示 AI 聊天（含左侧对话列表）。
       Page 的 auto-content-height 依赖 --vben-content-height 变量（原由后台布局观测写入），此处手动补齐 -->
       
  <div
    class="h-screen w-screen overflow-hidden bg-card"
    style="--vben-content-height: 100vh"
  >
    <AiChat />
  </div>
</template>
