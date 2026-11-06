<template>
  <div class="ad-page">
    <PageHeader title="我的设计任务" :subtitle="`当前 ${rows.length} 项任务，按状态优先排序`" />

    <div class="ad-card">
      <div class="ad-toolbar">
        <el-radio-group v-model="status" size="default" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="REVISION">修改中</el-radio-button>
          <el-radio-button value="DESIGNING">设计中</el-radio-button>
          <el-radio-button value="PENDING_CONFIRM">待客户确认</el-radio-button>
          <el-radio-button value="PENDING">待开始</el-radio-button>
          <el-radio-button value="CONFIRMED">已确认</el-radio-button>
        </el-radio-group>
        <div class="ad-spacer" />
        <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
      </div>
    </div>

    <div v-loading="loading" class="task-grid">
      <el-row v-if="rows.length" :gutter="16">
        <el-col v-for="row in rows" :key="row.id" :xs="24" :sm="12" :lg="8">
          <article class="task-card" :class="'bar-' + (TASK_STATUS[row.status]?.type || 'info')">
            <header class="tc-head">
              <span class="tc-name">{{ row.name }}</span>
              <StatusTag :status="row.status" :map="TASK_STATUS"
                         :text="taskStatusText(row.status, true)" />
            </header>

            <div class="tc-meta">
              <div class="tc-row">
                <span class="tc-label">所属订单</span>
                <span class="ad-mono tc-value">{{ row.orderNo }}</span>
              </div>
              <div class="tc-row">
                <span class="tc-label">客户</span>
                <span class="tc-value">{{ row.customerName || '-' }}</span>
              </div>
              <div class="tc-row">
                <span class="tc-label">物料</span>
                <span class="tc-value">
                  {{ MATERIAL_TYPE[row.materialType] || row.materialType }}
                  <template v-if="row.spec"> · {{ row.spec }}</template>
                  <template v-if="row.quantity"> × {{ row.quantity }}</template>
                </span>
              </div>
              <div class="tc-row">
                <span class="tc-label">截止日期</span>
                <span class="tc-value" :class="{ 'due-soon': isDueSoon(row.deadline, row.status) }">
                  {{ fmtDate(row.deadline) }}
                </span>
              </div>
              <!-- 客户意见：让设计人员不用点进详情就知道客户提了什么 -->
              <div v-if="row.feedbackCount" class="tc-row">
                <span class="tc-label">客户意见</span>
                <el-tooltip :content="row.latestFeedback || '（无内容）'" placement="top" :show-after="200">
                  <span class="tc-value feedback-flag">
                    第 {{ row.feedbackCount }} 轮 · {{ brief(row.latestFeedback) }}
                  </span>
                </el-tooltip>
              </div>
            </div>

            <footer class="tc-foot">
              <el-button v-if="row.status === 'PENDING'" type="primary" size="small" @click="start(row)">
                开始设计
              </el-button>
              <el-button size="small" @click="$router.push(`/tasks/${row.id}`)">查看详情</el-button>
            </footer>
          </article>
        </el-col>
      </el-row>

      <div v-else-if="!loading" class="ad-card">
        <EmptyBlock :description="status ? '该状态下暂无任务' : '暂时没有分派给你的设计任务'" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { TASK_STATUS, MATERIAL_TYPE, fmtDate, taskStatusText } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const rows = ref([])
const status = ref('')
const loading = ref(false)

function isDueSoon(deadline, st) {
  if (!deadline || st === 'CONFIRMED') return false
  return new Date(deadline).getTime() - Date.now() < 3 * 24 * 3600 * 1000
}

/** 客户意见摘要：卡片上只显示前 18 个字，完整内容看 tooltip 或任务详情 */
function brief(text) {
  if (!text) return ''
  return text.length > 18 ? text.slice(0, 18) + '…' : text
}

async function load() {
  loading.value = true
  try {
    rows.value = await api.myTasks(status.value || undefined)
  } finally {
    loading.value = false
  }
}

async function start(row) {
  await api.startTask(row.id)
  ElMessage.success(`【${row.name}】已开始设计`)
  load()
}

onMounted(load)
</script>

<style scoped>
.task-grid { min-height: 120px; }
.task-card {
  position: relative;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  box-shadow: var(--ad-shadow);
  padding: 16px 18px 14px 20px;
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: box-shadow .18s ease, transform .18s ease;
}
.task-card:hover { box-shadow: var(--ad-shadow-hover); transform: translateY(-2px); }

/* 左侧状态色条 */
.task-card::before {
  content: '';
  position: absolute; left: 0; top: 14px; bottom: 14px;
  width: 4px; border-radius: 0 4px 4px 0;
  background: #cbd5e1;
}
.task-card.bar-primary::before { background: #2563eb; }
.task-card.bar-warning::before { background: #f59e0b; }
.task-card.bar-danger::before { background: #ef4444; }
.task-card.bar-success::before { background: #16a34a; }
.task-card.bar-info::before { background: #94a3b8; }

.tc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.tc-name { font-size: 15px; font-weight: 600; color: var(--ad-text); line-height: 1.4; }

.tc-meta { display: flex; flex-direction: column; gap: 6px; }
.tc-row { display: flex; align-items: baseline; gap: 10px; font-size: 13px; }
.tc-label { color: var(--ad-text-muted); flex: 0 0 60px; }
.tc-value { color: var(--ad-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.feedback-flag { color: #b45309; cursor: help; }
.due-soon { color: #d97706; font-weight: 600; }

.tc-foot { display: flex; gap: 8px; padding-top: 10px; border-top: 1px dashed var(--ad-border); }
</style>
