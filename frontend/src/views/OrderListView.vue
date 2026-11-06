<template>
  <div class="ad-page">
    <PageHeader title="订单管理" :subtitle="`共 ${total} 笔订单，当前第 ${page} 页`">
      <el-button v-if="auth.role === 'SALES'" type="primary" @click="$router.push('/orders/new')">
        <el-icon><Plus /></el-icon>&nbsp;新建订单
      </el-button>
    </PageHeader>

    <!-- 大盘统计：一眼看出卡在哪个环节 -->
    <div class="stat-row">
      <button v-for="s in statCards" :key="s.key" type="button"
              class="stat-card" :class="[s.tone, { active: query.status === s.status }]"
              @click="filterBy(s.status)">
        <span class="stat-num">{{ s.value }}</span>
        <span class="stat-label">{{ s.label }}</span>
        <span class="stat-hint">{{ s.hint }}</span>
      </button>
    </div>

    <div v-if="!statsOk" class="stat-warn">
      <el-icon><WarningFilled /></el-icon>
      <span>统计暂不可用：后端版本较旧（缺少 /orders/stats 接口），数字显示为「—」。重新打包后端即可恢复。</span>
    </div>

    <!-- 筛选 -->
    <div class="ad-card">
      <div class="ad-toolbar">
        <el-input v-model="query.keyword" placeholder="订单编号 / 标题" clearable style="width: 220px"
                  @keyup.enter="onSearch" @clear="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.status" placeholder="订单状态" clearable style="width: 150px" @change="onSearch">
          <el-option v-for="(v, k) in ORDER_STATUS" :key="k" :label="v.label" :value="k" />
        </el-select>

        <el-select v-if="auth.role !== 'DESIGNER'" v-model="query.customerId" placeholder="客户"
                   clearable filterable style="width: 180px" @change="onSearch">
          <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>

        <el-select v-if="auth.role === 'MANAGER'" v-model="query.designerId" placeholder="设计人员"
                   clearable style="width: 150px" @change="onSearch">
          <el-option v-for="d in designers" :key="d.id" :label="d.realName" :value="d.id" />
        </el-select>

        <el-checkbox v-model="query.overdue" @change="onSearch">只看逾期</el-checkbox>

        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>

      <div v-if="auth.role === 'DESIGNER'" class="scope-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>当前视图只包含“分派给你任务”的订单</span>
      </div>
    </div>

    <!-- 列表 -->
    <div class="ad-card ad-card--flush">
      <el-table :data="rows" v-loading="loading" row-key="id" @row-dblclick="goDetail">
        <!-- 流程：一个标签说清状态，一条细线说清走到了哪 -->
        <el-table-column label="流程" width="132">
          <template #default="{ row }">
            <div class="flow-cell">
              <StatusTag :status="row.status" :map="ORDER_STATUS" />
              <el-progress v-if="row.status !== 'CANCELLED'"
                           :percentage="flowPercent(row.status)"
                           :stroke-width="4"
                           :show-text="false"
                           :color="flowColor(row.status)" />
              <span v-else class="flow-cancel">流程已终止</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="orderNo" label="订单编号" width="152">
          <template #default="{ row }">
            <span class="ad-mono order-no" @click="goDetail(row)">{{ row.orderNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="订单标题" min-width="190" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户" min-width="150" show-overflow-tooltip />

        <el-table-column label="物料进度" width="150">
          <template #default="{ row }">
            <div class="progress-cell">
              <el-progress :percentage="percent(row)" :stroke-width="6" :show-text="false"
                           :color="percent(row) === 100 ? '#16a34a' : '#2563eb'" />
              <span class="ad-mono progress-text">{{ row.taskConfirmed }}/{{ row.taskTotal }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="整体截止" width="132" align="center">
          <template #default="{ row }">
            <div class="due-cell">
              <span class="ad-mono" :class="deadlineMeta(row.deadline, row.status).cls">
                {{ fmtDate(row.deadline) }}
              </span>
              <span v-if="deadlineMeta(row.deadline, row.status).text"
                    class="due-left" :class="deadlineMeta(row.deadline, row.status).cls">
                {{ deadlineMeta(row.deadline, row.status).text }}
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="创建人" width="132">
          <template #default="{ row }">
            <div class="creator">
              <span class="creator-name">{{ row.createdByName }}</span>
              <!-- 后端未返回账号时（旧版本 jar）只显示姓名，避免出现孤零零的 @ -->
              <span v-if="row.createdByAccount" class="creator-account">@{{ row.createdByAccount }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="158" align="center">
          <template #default="{ row }"><span class="ad-muted">{{ fmtTime(row.createdAt) }}</span></template>
        </el-table-column>

        <el-table-column label="操作" width="86" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyBlock :description="hasFilter ? '没有符合筛选条件的订单，试试清空条件' : '还没有订单，去登记第一笔委托'" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[10, 20, 50]"
                       :total="total" background layout="total, sizes, prev, pager, next, jumper"
                       @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ORDER_STATUS, fmtDate, fmtTime, deadlineMeta } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const customers = ref([])
const designers = ref([])
const loading = ref(false)
const stats = ref({})
/** 统计接口是否可用；后端版本较旧时显示「—」而不是误导人的 0 */
const statsOk = ref(true)

const query = reactive({ keyword: '', status: '', customerId: null, designerId: null, overdue: false })

const hasFilter = computed(() =>
  Boolean(query.keyword || query.status || query.customerId || query.designerId || query.overdue)
)

function percent(row) {
  const t = Number(row.taskTotal || 0)
  if (!t) return 0
  return Math.round((Number(row.taskConfirmed || 0) / t) * 100)
}

/** 大盘卡片：点一下就按对应状态筛选 */
const statCards = computed(() => {
  const s = stats.value || {}
  const v = (key) => (statsOk.value ? (s[key] ?? 0) : '—')
  return [
    { key: 'pending', label: '待确认订单', value: v('orderPendingConfirm'), hint: '等负责人确认', status: 'PENDING_CONFIRM', tone: 'tone-amber' },
    { key: 'design', label: '待设计物料', value: v('taskPending'), hint: '未开始设计', status: '', tone: 'tone-blue' },
    { key: 'designing', label: '设计中物料', value: v('taskDesigning'), hint: '设计人员在做', status: 'IN_PROGRESS', tone: 'tone-indigo' },
    { key: 'confirm', label: '待客户确认', value: v('taskPendingConfirm'), hint: '等客户经理记录', status: '', tone: 'tone-orange' },
    { key: 'deliver', label: '可交付订单', value: v('orderDeliverable'), hint: '全部物料已确认', status: 'IN_PROGRESS', tone: 'tone-green' }
  ]
})

/**
 * 流程进度：待确认 20% → 进行中 60% → 已交付 100%。
 * 只用一条细线表达"走到哪了"，不额外堆文字，避免和状态标签信息重复。
 */
function flowPercent(status) {
  if (status === 'PENDING_CONFIRM') return 20
  if (status === 'IN_PROGRESS') return 60
  if (status === 'COMPLETED') return 100
  return 0
}

function flowColor(status) {
  if (status === 'COMPLETED') return '#16a34a'
  return '#2563eb'
}

async function loadStats() {
  try {
    stats.value = await api.orderStats()
    statsOk.value = true
  } catch {
    // 后端还是旧版本（没有 /orders/stats）：显示「—」并给出提示，而不是让人误以为全是 0
    stats.value = {}
    statsOk.value = false
  }
}

function filterBy(status) {
  query.status = status || ''
  page.value = 1
  load()
}

function isDueSoon(deadline, status) {
  if (!deadline || status === 'COMPLETED' || status === 'CANCELLED') return false
  const diff = new Date(deadline).getTime() - Date.now()
  return diff < 3 * 24 * 3600 * 1000
}

function goDetail(row) {
  router.push(`/orders/${row.id}`)
}

async function load() {
  loading.value = true
  try {
    const data = await api.orders({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      customerId: query.customerId || undefined,
      designerId: query.designerId || undefined,
      overdue: query.overdue || undefined,
      page: page.value,
      size: size.value
    })
    loadStats()
    rows.value = Array.isArray(data) ? data : (data?.list || [])
    total.value = Array.isArray(data) ? data.length : (data?.total || 0)
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 1
  load()
}

function onReset() {
  Object.assign(query, { keyword: '', status: '', customerId: null, designerId: null, overdue: false })
  page.value = 1
  load()
}

function onSizeChange() {
  page.value = 1
  load()
}

function applyRouteQuery() {
  const q = route.query
  query.keyword = q.keyword ? String(q.keyword) : ''
  query.status = q.status ? String(q.status) : ''
  query.customerId = q.customerId ? Number(q.customerId) : null
  query.designerId = q.designerId ? Number(q.designerId) : null
  query.overdue = q.overdue === 'true'
}

// 工作台/异常管理通过路由参数跳转过来：同步筛选条件后重新查询
watch(() => route.query, () => {
  applyRouteQuery()
  page.value = 1
  load()
}, { immediate: true })

onMounted(() => {
  if (auth.role !== 'DESIGNER') {
    api.customers().then(list => (customers.value = list)).catch(() => {})
  }
  if (auth.role === 'MANAGER') {
    api.designers().then(list => (designers.value = list)).catch(() => {})
  }
})
</script>

<style scoped>
.scope-hint {
  margin-top: 12px;
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--ad-text-secondary);
  background: #f7f9fd;
  border: 1px dashed var(--ad-border);
  border-radius: var(--ad-radius-sm);
  padding: 7px 12px;
}
.order-no { color: var(--ad-primary); cursor: pointer; font-size: 13px; }
.order-no:hover { text-decoration: underline; }
.progress-cell { display: flex; align-items: center; gap: 8px; }
.progress-cell :deep(.el-progress) { flex: 1; }
.progress-text { font-size: 12px; color: var(--ad-text-secondary); min-width: 34px; }
.due-soon { color: #d97706; font-weight: 600; }
.creator { display: flex; flex-direction: column; line-height: 1.35; }
.creator-name { color: var(--ad-text); }
.creator-account { font-size: 11px; color: var(--ad-text-muted); font-family: Consolas, monospace; }
.pager { display: flex; justify-content: flex-end; padding: 14px 18px; border-top: 1px solid var(--ad-border); }

/* ---------- 大盘统计卡 ---------- */
.stat-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}
.stat-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 14px 16px;
  text-align: left;
  font-family: inherit;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  box-shadow: var(--ad-shadow);
  cursor: pointer;
  transition: box-shadow .16s ease, transform .16s ease, border-color .16s ease;
  overflow: hidden;
}
.stat-card::before {
  content: '';
  position: absolute; left: 0; top: 0; bottom: 0;
  width: 4px;
}
.stat-card:hover { box-shadow: var(--ad-shadow-hover); transform: translateY(-2px); }
.stat-card.active { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.stat-num {
  font-size: 26px; font-weight: 700; line-height: 1.1;
  color: var(--ad-text);
  font-variant-numeric: tabular-nums;
}
.stat-label { font-size: 13px; font-weight: 500; color: var(--ad-text); }
.stat-hint { font-size: 11px; color: var(--ad-text-muted); }

.tone-amber::before { background: #f59e0b; }
.tone-amber .stat-num { color: #b45309; }
.tone-blue::before { background: #3b82f6; }
.tone-blue .stat-num { color: #1d4ed8; }
.tone-indigo::before { background: #6366f1; }
.tone-indigo .stat-num { color: #4338ca; }
.tone-orange::before { background: #f97316; }
.tone-orange .stat-num { color: #c2410c; }
.tone-green::before { background: #16a34a; }
.tone-green .stat-num { color: #15803d; }

.stat-warn {
  display: flex; align-items: center; gap: 8px;
  font-size: 12px;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: var(--ad-radius-sm);
  padding: 8px 14px;
}

@media (max-width: 1280px) {
  .stat-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

/* ---------- 流程 ---------- */
.flow-cell { display: flex; flex-direction: column; gap: 7px; align-items: flex-start; }
/* 进度条与标签左对齐，宽度固定，避免不同状态之间左右跳动 */
.flow-cell :deep(.el-progress) { width: 76px; }
.flow-cancel { font-size: 11px; color: var(--ad-text-muted); padding-left: 1px; }

/* ---------- 截止日期 ---------- */
.due-cell { display: flex; flex-direction: column; gap: 1px; line-height: 1.35; }
.due-left { font-size: 11px; }
</style>
