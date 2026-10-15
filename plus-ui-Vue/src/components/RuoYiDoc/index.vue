<template>
  <div>
    <svg-icon icon-class="question" @click="goto" />
  </div>
</template>

<script setup lang="ts">
const url = ref('https://gitee.com/zl3624/biz-buddy/blob/master/docs/README.md');

function goto() {
  window.open(url.value);
}
</script>
