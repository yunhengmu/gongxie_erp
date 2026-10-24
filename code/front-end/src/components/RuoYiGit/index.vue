<template>
  <div>
    <svg-icon icon-class="gitee" @click="goto" />
  </div>
</template>

<script setup lang="ts">
const url = ref('https://gitee.com/zl3624/biz-buddy');

function goto() {
  window.open(url.value);
}
</script>
