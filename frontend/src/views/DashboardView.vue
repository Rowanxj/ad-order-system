<template>
  <div class="ad-page" v-loading="loading">
    <PageHeader title="工作台" :subtitle="subtitle">
      <el-button v-if="auth.role === 'SALES'" type="primary" @click="$router.push('/orders/new')">
        <el-icon><Plus /></el-icon>&nbsp;新建订单
      </el-button>
      <el-button v-if="auth.role !== 'DESIGNER'" @click="$router.push('/exceptions')">
        <el-icon><Warning /></el-icon>&nbsp;异常管理
      </el-button>
    </PageHeader>

    <el-row :gutter="12" class="metric-row">
      <el-col v-for="m in metrics" :key="m.label" :xs="12" :sm="8" :lg="4">
        <div class="metric" :class="m.tone" @click="m.to && $router.push(m.to)">
          <div class="metric-top">
            <span class="metric-label">{{ m.label }}</span>
            <el-icon :size="18"><component :is="m.icon" /></el-icon>
          </div>
          <div class="metric-value">{{ m.value }}</div>
          <div class="metric-hint">{{ m.hint }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="14">
      <el-col :xs="24" :lg="auth.role === 'DESIGNER' ? 24 : 15">
        <div class="ad-card">
          <div class="card-head">
            <span class="ad-section-title">{{ auth.role === 'DESIGNER' ? '我的任务' : '优先处理的任务' }}</span>
            <span class="ad-muted">{{ auth.role === 'DESIGNER' ? '按状态优先' : '逾期与待确认优先' }}</span>
          </div>

          <el-table :data="todoTasks" row-key="id" size="small" @row-dblclick="openTask">
            <el-table-column label="任务" min-width="190">
              <template #default="{ row }">
                <div class="task-cell">
                  <span class="task-name">{{ row.name }}</span>
                  <span class="task-order">{{ row.orderNo }} · {{ row.orderTitle }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }">
                <StatusTag :status="row.status" :map="TASK_STATUS"
                           :text="taskStatusText(row.status, row.assigneeId)" />
              </template>
            </el-table-column>
            <el-table-column label="截止" width="112">
              <template #default="{ row }">
                <span :class="deadlineMeta(row.deadline, row.status).cls"
                      :title="deadlineMeta(row.deadline, row.status).text">
                  {{ fmtDate(row.deadline) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="负责人" width="100">
              <template #default="{ row }">
                <span :class="{ 'ad-muted': !row.assigneeName }">{{ row.assigneeName || '未分派' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="76" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTask(row)">查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyBlock :description="auth.role === 'DESIGNER' ? '暂时没有待处理任务' : '没有需要优先处理的任务'" />
            </template>
          </el-table>
        </div>
      </el-col>

      <el-col v-if="auth.role !== 'DESIGNER'" :xs="24" :lg="9">
        <div class="ad-card">
          <div class="card-head">
            <span class="ad-section-title">逾期订单</span>
            <span class="ad-muted">共 {{ counts.overdueOrders }} 笔</span>
          </div>
          <div v-for="o in overdueOrders" :key="o.id" class="mini-row" @click="$router.push('/orders/' + o.id)">
            <div class="mini-main">
              <div class="mini-title">{{ o.title }}</div>
              <div class="mini-sub">{{ o.orderNo }} · {{ o.customerName }}</div>
            </div>
            <div class="mini-right">
              <span class="overdue-text">逾期 {{ daysOverdue(o.deadline) }} 天</span>
              <span class="ad-muted">{{ fmtDate(o.deadline) }}</span>
            </div>
          </div>
          <EmptyBlock v-if="!overdueOrders.length" description="没有逾期订单" />
        </div>
      </el-col>
    </el-row>

    <div class="quick-row">
      <span class="ad-muted">快捷入口：</span>
      <el-button link type="primary" @click="$router.push('/orders')">订单管理</el-button>
      <el-button v-if="auth.role === 'DESIGNER'" link type="primary" @click="$router.push('/my-tasks')">我的任务</el-button>
      <el-button v-if="auth.role !== 'DESIGNER'" link type="primary" @click="$router.push('/exceptions')">异常管理</el-button>
      <el-button v-if="['SALES', 'MANAGER'].includes(auth.role)" link type="primary" @click="$router.push('/customers')">客户管理</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { TASK_STATUS, fmtDate, taskStatusText, deadlineMeta } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)

const counts = reactive({
  pendingConfirmOrders: 0,
  inProgressOrders: 0,
  overdueOrders: 0,
  pendingTasks: 0,
  unassignedTasks: 0,
  designingTasks: 0,
  revisionTasks: 0,
  pendingConfirmTasks: 0,
  overdueTasks: 0,
  confirmedTasks: 0
})
const overdueOrders = ref([])
const todoTasks = ref([])

const subtitle = computed(() => {
  const roleText = { SALES: '客户经理', MANAGER: '业务负责人', DESIGNER: '设计制作人员' }[auth.role] || ''
  return `${auth.user?.realName || ''} · ${roleText} · 先处理逾期和等待确认的事项`
})

const metrics = computed(() => {
  if (auth.role === 'DESIGNER') {
    return [
      { label: '待开始', value: counts.pendingTasks, hint: '已分派、还没点开始', tone: 'info', icon: 'Clock', to: '/my-tasks' },
      { label: '设计中', value: counts.designingTasks, hint: '正在做', tone: 'primary', icon: 'Brush', to: '/my-tasks' },
      { label: '修改中', value: counts.revisionTasks, hint: '客户提了意见', tone: 'warning', icon: 'EditPen', to: '/my-tasks' },
      { label: '待客户确认', value: counts.pendingConfirmTasks, hint: '等客户经理记录结果', tone: 'primary', icon: 'Bell', to: '/my-tasks' },
      { label: '逾期任务', value: counts.overdueTasks, hint: '截止日期已过', tone: 'danger', icon: 'Warning', to: '/my-tasks' },
      { label: '已确认', value: counts.confirmedTasks, hint: '客户已确认', tone: 'success', icon: 'CircleCheck', to: '/my-tasks' }
    ]
  }
  return [
    { label: '待确认订单', value: counts.pendingConfirmOrders, hint: '等负责人确认范围', tone: 'warning', icon: 'Document', to: '/orders?status=PENDING_CONFIRM' },
    { label: '待分派任务', value: counts.unassignedTasks, hint: '已确认订单里没分人', tone: 'danger', icon: 'User', to: '/exceptions?type=TASK_UNASSIGNED' },
    { label: '待客户确认', value: counts.pendingConfirmTasks, hint: '设计已提交，等记录结果', tone: 'primary', icon: 'Bell', to: '/exceptions?type=TASK_WAITING_CONFIRM' },
    { label: '逾期任务', value: counts.overdueTasks, hint: '物料截止已过', tone: 'danger', icon: 'Warning', to: '/exceptions?type=TASK_OVERDUE' },
    { label: '逾期订单', value: counts.overdueOrders, hint: '整单截止已过', tone: 'danger', icon: 'AlarmClock', to: '/orders?overdue=true' },
    { label: '进行中订单', value: counts.inProgressOrders, hint: '已确认、未交付', tone: 'primary', icon: 'Tickets', to: '/orders?status=IN_PROGRESS' }
  ]
})

function startOfToday() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d
}

function parseDate(v) {
  const [y, m, d] = String(v).slice(0, 10).split('-').map(Number)
  return new Date(y, m - 1, d)
}

function isOverdue(task) {
  if (!task.deadline || task.status === 'CONFIRMED') return false
  return parseDate(task.deadline) < startOfToday()
}

function daysOverdue(deadline) {
  if (!deadline) return 0
  const diff = startOfToday() - parseDate(deadline)
  return Math.max(0, Math.round(diff / 86400000))
}

function priority(task) {
  if (isOverdue(task)) return 0
  return { PENDING_CONFIRM: 1, REVISION: 2, DESIGNING: 3, PENDING: 4, CONFIRMED: 5 }[task.status] ?? 6
}

function openTask(row) {
  router.push('/tasks/' + row.id)
}

async function load() {
  loading.value = true
  try {
    if (auth.role === 'DESIGNER') {
      const list = (await api.myTasks()) || []
      counts.pendingTasks = list.filter(t => t.status === 'PENDING').length
      counts.designingTasks = list.filter(t => t.status === 'DESIGNING').length
      counts.revisionTasks = list.filter(t => t.status === 'REVISION').length
      counts.pendingConfirmTasks = list.filter(t => t.status === 'PENDING_CONFIRM').length
      counts.overdueTasks = list.filter(isOverdue).length
      counts.confirmedTasks = list.filter(t => t.status === 'CONFIRMED').length
      todoTasks.value = [...list]
        .filter(t => t.status !== 'CONFIRMED')
        .sort((a, b) => priority(a) - priority(b) || String(a.deadline || '').localeCompare(String(b.deadline || '')))
        .slice(0, 8)
      return
    }

    const [pending, inProgress, overdue, taskPage] = await Promise.all([
      api.orders({ status: 'PENDING_CONFIRM', page: 1, size: 1 }),
      api.orders({ status: 'IN_PROGRESS', page: 1, size: 1 }),
      api.orders({ overdue: true, page: 1, size: 5 }),
      api.tasks({ page: 1, size: 100 })
    ])

    counts.pendingConfirmOrders = pending.total || 0
    counts.inProgressOrders = inProgress.total || 0
    counts.overdueOrders = overdue.total || 0
    overdueOrders.value = overdue.list || []

    const tasks = (taskPage.list || [])
      .filter(t => ['PENDING_CONFIRM', 'IN_PROGRESS'].includes(t.orderStatus))
    counts.pendingTasks = tasks.filter(t => t.status === 'PENDING').length
    counts.unassignedTasks = tasks.filter(t => t.status === 'PENDING' && !t.assigneeId).length
    counts.pendingConfirmTasks = tasks.filter(t => t.status === 'PENDING_CONFIRM').length
    counts.overdueTasks = tasks.filter(isOverdue).length
    todoTasks.value = tasks
      .filter(t => t.status !== 'CONFIRMED')
      .sort((a, b) => priority(a) - priority(b) || String(a.deadline || '').localeCompare(String(b.deadline || '')))
      .slice(0, 8)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.metric-row { margin-bottom: 2px; }
:deep(.el-col) { min-width: 0; }

.metric {
  position: relative;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  padding: 14px 16px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: box-shadow .18s ease, transform .18s ease;
  overflow: hidden;
}
.metric:hover { box-shadow: var(--ad-shadow-hover); transform: translateY(-2px); }
.metric::before {
  content: '';
  position: absolute; left: 0; top: 12px; bottom: 12px; width: 4px;
  border-radius: 0 4px 4px 0; background: #cbd5e1;
}
.metric.warning::before { background: #f59e0b; }
.metric.primary::before { background: #2563eb; }
.metric.danger::before { background: #ef4444; }
.metric.success::before { background: #16a34a; }
.metric.info::before { background: #94a3b8; }

.metric-top { display: flex; align-items: center; justify-content: space-between; color: var(--ad-text-secondary); }
.metric-label { font-size: 13px; }
.metric-value { font-size: 30px; line-height: 1.15; font-weight: 700; color: var(--ad-text); margin: 6px 0 2px; }
.metric.warning .metric-value { color: #b45309; }
.metric.danger .metric-value { color: #dc2626; }
.metric.primary .metric-value { color: #1d4ed8; }
.metric.success .metric-value { color: #15803d; }
.metric-hint { font-size: 12px; color: var(--ad-text-muted); }

.card-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px 10px; }
.card-head .ad-section-title { margin-bottom: 0; }

.task-cell { display: flex; flex-direction: column; line-height: 1.35; }
.task-name { font-weight: 600; color: var(--ad-text); }
.task-order { font-size: 12px; color: var(--ad-text-muted); }

.mini-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 18px; border-top: 1px solid var(--ad-border); cursor: pointer; }
.mini-row:hover { background: #f8fafc; }
.mini-main { min-width: 0; }
.mini-title { font-size: 13px; font-weight: 600; color: var(--ad-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mini-sub { font-size: 12px; color: var(--ad-text-muted); }
.mini-right { text-align: right; display: flex; flex-direction: column; gap: 2px; font-size: 12px; }
.overdue-text { color: #dc2626; font-weight: 600; }

.quick-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 14px; font-size: 13px; }
</style>
