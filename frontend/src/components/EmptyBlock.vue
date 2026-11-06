<template>
  <div class="empty-block">
    <el-empty :description="description" :image-size="96" />
  </div>
</template>

<script setup>
defineProps({
  description: { type: String, default: '暂无数据' }
})
</script>

<style scoped>
.empty-block { padding: 18px 0; }
</style>
