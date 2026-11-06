<template>
  <el-tag :type="info?.type || 'info'" :effect="effect" size="small" disable-transitions>
    {{ text || info?.label || status }}
  </el-tag>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 状态标签：统一从 constants.js 的状态映射取中文与配色。
 * 页面里不许再手写「状态码 → 中文」，否则新增状态必然漏一处。
 *
 * text 用于「同一个状态码要分情况显示」的场景，
 * 例如任务 PENDING 有分派人时显示“待开始”（见 constants.js 的 taskStatusText）。
 */
const props = defineProps({
  status: { type: String, required: true },
  map: { type: Object, required: true },
  effect: { type: String, default: 'light' },
  text: { type: String, default: '' }
})

const info = computed(() => props.map?.[props.status])
</script>

