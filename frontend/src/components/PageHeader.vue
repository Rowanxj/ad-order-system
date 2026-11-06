<template>
  <div class="page-header">
    <div class="ph-text">
      <h2 class="ph-title">{{ title }}</h2>
      <p v-if="subtitle" class="ph-sub">{{ subtitle }}</p>
    </div>
    <div class="ph-actions">
      <slot />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.ph-title {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  color: var(--ad-text);
  letter-spacing: .2px;
}
.ph-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--ad-text-secondary);
}
.ph-actions { display: flex; gap: 10px; align-items: center; }
</style>
