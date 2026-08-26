<script lang="ts" setup>
    import { useVbenDrawer, VbenButton } from '@vben/common-ui';

    const [Drawer, drawerApi] = useVbenDrawer();
</script>

<template>
    <VbenButton @click="() => drawerApi.open()">Open</VbenButton>
    <Drawer class="w-150" title="基础示例"> 
      <div>
        <VbenButton @click="() => drawerApi.close()">Close</VbenButton>
      </div>
    </Drawer>
</template>
