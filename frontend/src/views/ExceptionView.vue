<template>
  <div class="ad-page" v-loading="loading">
    <PageHeader title="异常管理" subtitle="逾期、待分派、待确认、已延期，集中处理">
      <el-button @click="load"><el-icon><Refresh /></el-icon>&nbsp;刷新</el-button>
    </PageHeader>

    <!-- 筛选 -->
    <div class="ad-card">
      <div class="ad-toolbar filter-bar">
        <el-radio-group v-model="tab" @change="onFilter">
          <el-radio-button value="ALL">全部 {{ items.length }}</el-radio-button>
          <el-radio-button v-for="t in typeTabs" :key="t.type" :value="t.type">
            {{ t.label }} {{ t.count }}
          </el-radio-button>
        </el-radio-group>
        <div class="ad-spacer" />
        <el-input v-model="keyword" placeholder="订单号 / 任务 / 客户" clearable style="width: 200px"
                  @keyup.enter="onFilter" @clear="onFilter">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
    </div>

    <div class="split">
      <!-- 左：压缩异常列表 -->
      <div class="split-left">
        <div class="ad-card ad-card--flush list-card">
          <el-table :data="pagedItems" size="small" row-key="key" highlight-current-row
                    :current-row-key="selectedKey" @row-click="selectRow">
            <el-table-column label="类型" width="92">
              <template #default="{ row }">
                <el-tag :type="TYPE_META[row.type].tag" effect="plain" size="small">
                  {{ TYPE_META[row.type].label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="对象" min-width="150">
              <template #default="{ row }">
                <div class="obj-cell">
                  <span class="obj-title">{{ row.taskName || row.title }}</span>
                  <span class="obj-sub">{{ row.orderNo }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="逾期/等待" width="96" align="center">
              <template #default="{ row }">
                <span :class="row.days > 0 ? 'danger-text' : 'ad-muted'">{{ row.reason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="负责人" width="76" align="center">
              <template #default="{ row }">
                <span :class="{ 'ad-muted': !row.assigneeName }">{{ row.assigneeName || '未分派' }}</span>
              </template>
            </el-table-column>
            <template #empty><EmptyBlock :description="items.length ? '当前筛选下没有异常' : '太好了，目前没有异常'" /></template>
          </el-table>

          <div class="pager">
            <el-pagination v-model:current-page="page" v-model:page-size="size"
                           :page-sizes="[10, 20, 50]" :total="filteredItems.length" background
                           layout="total, sizes, prev, pager, next" @current-change="onPageChange"
                           @size-change="onSizeChange" />
          </div>
        </div>
      </div>

      <!-- 右：选中异常详情 -->
      <div class="split-right">
        <div class="ad-card detail-card" v-if="selected">
          <div class="detail-head">
            <div class="detail-title-wrap">
              <div class="detail-title">{{ selected.taskName || selected.title }}</div>
              <div class="ad-muted">{{ selected.orderNo }}<template v-if="selected.taskName"> · {{ selected.title }}</template></div>
            </div>
            <el-tag :type="TYPE_META[selected.type].tag">{{ TYPE_META[selected.type].label }}</el-tag>
          </div>

          <el-descriptions :column="2" size="small" border class="detail-desc">
            <el-descriptions-item label="客户">{{ selected.customerName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="截止日期">{{ fmtDate(selected.deadline) }}</el-descriptions-item>
            <el-descriptions-item label="负责人">{{ selected.assigneeName || '未分派' }}</el-descriptions-item>
            <el-descriptions-item label="异常信息">
              <span :class="selected.days > 0 ? 'danger-text' : ''">{{ selected.reason }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="suggestion">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ suggestion }}</span>
          </div>

          <div class="actions">
            <el-button type="primary" size="small" @click="$router.push(selected.to)">查看详情</el-button>
            <template v-if="isSales && selected.taskId && selected.status === 'PENDING_CONFIRM'">
              <el-button type="success" size="small" @click="doCustomerConfirm">客户已确认</el-button>
              <el-button type="warning" plain size="small" @click="openFeedback">记录修改意见</el-button>
            </template>
            <el-button v-if="actionLabel(selected) !== '查看'" size="small" @click="handleAction(selected)">
              {{ actionLabel(selected) }}
            </el-button>
          </div>
        </div>
        <div class="ad-card" v-else>
          <EmptyBlock description="从左侧选一条异常查看处理建议" />
        </div>
      </div>
    </div>

    <!-- 直接分派 -->
    <el-dialog v-model="assignDlg" title="分派任务" width="420px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                :title="`物料：${assignTarget.taskName || ''}`" />
      <el-select v-model="assigneeId" placeholder="选择设计制作人员" style="width: 100%" filterable>
        <el-option v-for="d in designers" :key="d.id" :label="`${d.realName}（${d.username}）`" :value="d.id" />
      </el-select>
      <template #footer>
        <el-button @click="assignDlg = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="saveAssign">确定分派</el-button>
      </template>
    </el-dialog>

    <!-- 记录客户修改意见（客户经理） -->
    <el-dialog v-model="feedbackDlg" title="记录客户修改意见" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                title="记录后任务进入「修改中」，设计人员需提交新版本" />
      <el-input v-model="feedbackContent" type="textarea" :rows="4" maxlength="500" show-word-limit
                placeholder="填写客户对当前版本的意见，尽量保留客户原话" />
      <template #footer>
        <el-button @click="feedbackDlg = false">取消</el-button>
        <el-button type="primary" :loading="savingFeedback" @click="saveFeedback">保存</el-button>
      </template>
    </el-dialog>

    <!-- 延长订单截止 -->
    <el-dialog v-model="deadlineDlg" title="延长订单截止日期" width="380px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="延长后写入变更记录，并显示为「已延期」" />
      <el-date-picker v-model="newDeadline" type="date" value-format="YYYY-MM-DD"
                      :disabled-date="disabledPast" style="width: 100%" />
      <div class="ad-muted" style="margin-top: 10px">
        当前整体截止：{{ fmtDate(deadlineTarget.deadline) }}；新日期不能早于今天。
      </div>
      <template #footer>
        <el-button @click="deadlineDlg = false">取消</el-button>
        <el-button type="primary" :loading="savingDeadline" @click="saveExtend">保存</el-button>
      </template>
    </el-dialog>

    <!-- 延长物料截止 -->
    <el-dialog v-model="taskDeadlineDlg" title="延长物料截止日期" width="400px">
      <el-alert v-if="taskOrderDeadlinePast" type="error" :closable="false" style="margin-bottom: 12px"
                title="订单整体截止已过期，请先延长订单截止日期。" />
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                :title="`物料：${taskDeadlineTarget.taskName || ''}`" />
      <el-date-picker v-model="newTaskDeadline" type="date" value-format="YYYY-MM-DD"
                      :disabled-date="disabledTaskDate" style="width: 100%" />
      <div class="ad-muted" style="margin-top: 10px">
        当前物料截止：{{ fmtDate(taskDeadlineTarget.deadline) }}；订单整体截止：{{ fmtDate(taskDeadlineTarget.orderDeadline) }}。
        新日期不能早于今天，也不能晚于订单整体截止。
      </div>
      <template #footer>
        <el-button @click="taskDeadlineDlg = false">取消</el-button>
        <el-button type="primary" :loading="savingTaskDeadline" @click="saveTaskExtend">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { fmtDate } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const items = ref([])
const tab = ref('ALL')
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const selectedKey = ref('')

const assignDlg = ref(false)
const assignTarget = ref({})
const assigneeId = ref(null)
const designers = ref([])
const assigning = ref(false)

const deadlineDlg = ref(false)
const deadlineTarget = ref({})
const newDeadline = ref(null)
const savingDeadline = ref(false)

const taskDeadlineDlg = ref(false)
const taskDeadlineTarget = ref({})
const newTaskDeadline = ref(null)
const savingTaskDeadline = ref(false)

const feedbackDlg = ref(false)
const feedbackContent = ref('')
const savingFeedback = ref(false)

const TYPE_META = {
  ORDER_OVERDUE: { label: '订单逾期', tag: 'danger', level: 0 },
  ORDER_READY: { label: '待交付', tag: 'warning', level: 1 },
  ORDER_EXTENDED: { label: '已延期', tag: 'info', level: 2 },
  TASK_OVERDUE: { label: '任务逾期', tag: 'danger', level: 0 },
  TASK_UNASSIGNED: { label: '待分派', tag: 'warning', level: 1 },
  TASK_WAITING_CONFIRM: { label: '待客户确认', tag: 'primary', level: 2 },
  TASK_EXTENDED: { label: '已延期', tag: 'info', level: 2 }
}

const isManager = computed(() => auth.role === 'MANAGER')
const isSales = computed(() => auth.role === 'SALES')
const selected = computed(() => items.value.find(i => i.key === selectedKey.value))

const typeTabs = computed(() =>
  Object.keys(TYPE_META)
    .map(type => ({ type, label: TYPE_META[type].label, count: items.value.filter(i => i.type === type).length }))
    .filter(t => t.count > 0)
)

const filteredItems = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return items.value.filter(i => {
    if (tab.value !== 'ALL' && i.type !== tab.value) return false
    if (!kw) return true
    return [i.orderNo, i.title, i.taskName, i.customerName, i.assigneeName]
      .filter(Boolean).some(v => String(v).toLowerCase().includes(kw))
  })
})
const pagedItems = computed(() =>
  filteredItems.value.slice((page.value - 1) * size.value, page.value * size.value)
)

const taskOrderDeadlinePast = computed(() => {
  const od = taskDeadlineTarget.value.orderDeadline
  return !!od && parseDate(od) < startOfToday()
})

const suggestion = computed(() => {
  if (!selected.value) return ''
  const map = {
    ORDER_OVERDUE: '先和客户确认是否同意延期；可延长订单截止，或查看具体逾期物料。',
    ORDER_EXTENDED: '订单已延期，关注新的整体截止日期；有需要可再次延长。',
    ORDER_READY: '全部未取消物料已确认，负责人可以交付订单。',
    TASK_OVERDUE: '可延长物料截止，或查看设计进度与客户意见。',
    TASK_UNASSIGNED: '选择设计制作人员直接分派；分派后任务进入待开始。',
    TASK_WAITING_CONFIRM: '客户经理联系客户确认当前版本，再记录客户意见或客户确认结果。',
    TASK_EXTENDED: '物料已延期，关注新的截止日期；可查看任务详情或再次延长。'
  }
  return map[selected.value.type] || ''
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
function parseDateTime(v) {
  if (!v) return null
  const d = new Date(String(v).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}
function daysBetween(from) {
  if (!from) return 0
  return Math.max(0, Math.round((startOfToday() - parseDateTime(from)) / 86400000))
}
function daysOverdue(deadline) {
  if (!deadline) return 0
  return Math.max(0, Math.round((startOfToday() - parseDate(deadline)) / 86400000))
}
function disabledPast(d) {
  return d < startOfToday()
}
function disabledTaskDate(d) {
  if (disabledPast(d)) return true
  const od = taskDeadlineTarget.value.orderDeadline
  return !!od && d > parseDate(od)
}

function actionLabel(row) {
  if (row.type === 'TASK_UNASSIGNED' && isManager.value) return '分派'
  if (['ORDER_OVERDUE', 'ORDER_EXTENDED', 'TASK_OVERDUE', 'TASK_EXTENDED'].includes(row.type)) return '延长截止'
  return '查看'
}
function handleAction(row) {
  if (row.type === 'TASK_UNASSIGNED' && isManager.value) return openAssign(row)
  if (['ORDER_OVERDUE', 'ORDER_EXTENDED'].includes(row.type)) return openExtend(row)
  if (['TASK_OVERDUE', 'TASK_EXTENDED'].includes(row.type)) return openTaskExtend(row)
  router.push(row.to)
}

function selectRow(row) {
  selectedKey.value = row.key
}
function onFilter() {
  page.value = 1
  selectedKey.value = filteredItems.value[0]?.key || ''
}
function onPageChange() {
  selectedKey.value = pagedItems.value[0]?.key || ''
}
function onSizeChange() {
  page.value = 1
  selectedKey.value = pagedItems.value[0]?.key || ''
}

function openAssign(row) {
  assignTarget.value = row
  assigneeId.value = null
  assignDlg.value = true
}
async function saveAssign() {
  if (!assigneeId.value) return ElMessage.warning('请选择设计制作人员')
  assigning.value = true
  try {
    await api.assignTask(assignTarget.value.taskId, assigneeId.value)
    ElMessage.success('分派成功')
    assignDlg.value = false
    load()
  } finally {
    assigning.value = false
  }
}

async function doCustomerConfirm() {
  await ElMessageBox.confirm(
    '确认客户已认可当前版本？确认后任务完成，不能再提交新版本。',
    '客户确认'
  )
  await api.confirmTask(selected.value.taskId)
  ElMessage.success('客户确认完成，任务已确认')
  load()
}

function openFeedback() {
  feedbackContent.value = ''
  feedbackDlg.value = true
}

async function saveFeedback() {
  if (!feedbackContent.value.trim()) return ElMessage.warning('请填写客户修改意见')
  savingFeedback.value = true
  try {
    await api.recordFeedback(selected.value.taskId, feedbackContent.value)
    ElMessage.success('修改意见已记录，任务进入修改中')
    feedbackDlg.value = false
    load()
  } finally {
    savingFeedback.value = false
  }
}

function openExtend(row) {
  deadlineTarget.value = row
  newDeadline.value = null
  deadlineDlg.value = true
}
async function saveExtend() {
  if (!newDeadline.value) return ElMessage.warning('请选择新的截止日期')
  savingDeadline.value = true
  try {
    await api.updateOrderDeadline(deadlineTarget.value.orderId, newDeadline.value)
    ElMessage.success('截止日期已延长，已标记为「已延期」')
    deadlineDlg.value = false
    load()
  } finally {
    savingDeadline.value = false
  }
}

function openTaskExtend(row) {
  taskDeadlineTarget.value = row
  newTaskDeadline.value = null
  taskDeadlineDlg.value = true
}
async function saveTaskExtend() {
  if (!newTaskDeadline.value) return ElMessage.warning('请选择新的截止日期')
  savingTaskDeadline.value = true
  try {
    await api.updateTaskDeadline(taskDeadlineTarget.value.taskId, newTaskDeadline.value)
    ElMessage.success('物料截止日期已延长，已标记为「已延期」')
    taskDeadlineDlg.value = false
    load()
  } finally {
    savingTaskDeadline.value = false
  }
}

function taskItem(t, type) {
  const days = type === 'TASK_WAITING_CONFIRM' ? daysBetween(t.updatedAt) : daysOverdue(t.deadline)
  const reason = type === 'TASK_WAITING_CONFIRM'
    ? (days > 0 ? `等待 ${days} 天` : '待处理')
    : (days > 0 ? `逾期 ${days} 天` : '今日到期')
  return {
    key: `${type}-${t.id}`,
    type,
    taskId: t.id,
    taskName: t.name,
    title: t.orderTitle,
    orderId: t.orderId,
    orderNo: t.orderNo,
    customerName: t.customerName,
    deadline: t.deadline,
    orderDeadline: t.orderDeadline,
    status: t.status,
    assigneeName: t.assigneeName,
    days,
    reason,
    to: '/tasks/' + t.id
  }
}

async function load() {
  loading.value = true
  try {
    const [overOrders, overTasks, unassigned, waiting, inProgress, extendedOrders, extendedTasks] = await Promise.all([
      api.orders({ overdue: true, page: 1, size: 100 }),
      api.tasks({ overdue: true, page: 1, size: 100 }),
      api.tasks({ unassigned: true, page: 1, size: 100 }),
      api.tasks({ status: 'PENDING_CONFIRM', page: 1, size: 100 }),
      api.orders({ status: 'IN_PROGRESS', page: 1, size: 100 }),
      api.orders({ extended: true, page: 1, size: 100 }),
      api.tasks({ extended: true, page: 1, size: 100 })
    ])

    const list = []
    const seenTasks = new Set()
    const seenOrders = new Set()

    for (const o of overOrders.list || []) {
      seenOrders.add(o.id)
      list.push({
        key: 'ORDER_OVERDUE-' + o.id, type: 'ORDER_OVERDUE',
        title: o.title, orderId: o.id, orderNo: o.orderNo, customerName: o.customerName,
        deadline: o.deadline, days: daysOverdue(o.deadline),
        reason: `逾期 ${daysOverdue(o.deadline)} 天`, assigneeName: '', to: '/orders/' + o.id
      })
    }

    for (const t of overTasks.list || []) {
      if (seenTasks.has(t.id)) continue
      seenTasks.add(t.id)
      list.push(taskItem(t, 'TASK_OVERDUE'))
    }

    for (const t of unassigned.list || []) {
      if (seenTasks.has(t.id)) continue
      if (!['PENDING_CONFIRM', 'IN_PROGRESS'].includes(t.orderStatus)) continue
      seenTasks.add(t.id)
      list.push({ ...taskItem(t, 'TASK_UNASSIGNED'), reason: '未分派' })
    }

    for (const t of waiting.list || []) {
      if (seenTasks.has(t.id)) continue
      if (!['PENDING_CONFIRM', 'IN_PROGRESS'].includes(t.orderStatus)) continue
      seenTasks.add(t.id)
      list.push(taskItem(t, 'TASK_WAITING_CONFIRM'))
    }

    for (const o of extendedOrders.list || []) {
      if (seenOrders.has(o.id)) continue
      seenOrders.add(o.id)
      list.push({
        key: 'ORDER_EXTENDED-' + o.id, type: 'ORDER_EXTENDED',
        title: o.title, orderId: o.id, orderNo: o.orderNo, customerName: o.customerName,
        deadline: o.deadline, days: 0, reason: `已延至 ${fmtDate(o.deadline)}`,
        assigneeName: '', to: '/orders/' + o.id
      })
    }

    for (const t of extendedTasks.list || []) {
      if (seenTasks.has(t.id)) continue
      if (!['PENDING_CONFIRM', 'IN_PROGRESS'].includes(t.orderStatus)) continue
      if (t.status === 'CONFIRMED') continue
      seenTasks.add(t.id)
      list.push({ ...taskItem(t, 'TASK_EXTENDED'), days: 0, reason: `已延至 ${fmtDate(t.deadline)}` })
    }

    for (const o of inProgress.list || []) {
      if (seenOrders.has(o.id)) continue
      if (Number(o.taskTotal) > 0 && Number(o.taskTotal) === Number(o.taskConfirmed)) {
        seenOrders.add(o.id)
        list.push({
          key: 'ORDER_READY-' + o.id, type: 'ORDER_READY',
          title: o.title, orderId: o.id, orderNo: o.orderNo, customerName: o.customerName,
          deadline: o.deadline, days: 0, reason: '待交付', assigneeName: '', to: '/orders/' + o.id
        })
      }
    }

    list.sort((a, b) => TYPE_META[a.type].level - TYPE_META[b.type].level || b.days - a.days)
    items.value = list
    if (!list.some(i => i.key === selectedKey.value)) {
      selectedKey.value = filteredItems.value[0]?.key || ''
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  const q = String(route.query.type || '')
  if (q && TYPE_META[q]) tab.value = q
  if (isManager.value) {
    api.designers().then(list => (designers.value = list)).catch(() => {})
  }
  load()
})
</script>

<style scoped>
.filter-bar { flex-wrap: wrap; gap: 8px; max-width: 100%; }
.split {
  display: grid;
  grid-template-columns: minmax(0, 11fr) minmax(0, 13fr);
  gap: 14px;
  align-items: start;
  min-width: 0;
  width: 100%;
  max-width: 100%;
}
.split > div { min-width: 0; }
@media (max-width: 1100px) { .split { grid-template-columns: minmax(0, 1fr); } }
.list-card { overflow: hidden; }
.detail-card { min-height: 420px; }
.detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.detail-title-wrap { min-width: 0; }
.detail-title { font-size: 17px; font-weight: 700; color: var(--ad-text); line-height: 1.35; }
.detail-desc { margin-bottom: 12px; }
.suggestion {
  display: flex; align-items: flex-start; gap: 8px;
  background: #f7f9fd; border: 1px solid var(--ad-border); border-radius: var(--ad-radius-sm);
  padding: 10px 12px; font-size: 13px; color: var(--ad-text-secondary); line-height: 1.6;
}
.actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.obj-cell { display: flex; flex-direction: column; line-height: 1.3; }
.obj-title { font-weight: 600; color: var(--ad-text); }
.obj-sub { font-size: 12px; color: var(--ad-text-muted); }
.danger-text { color: #dc2626; font-weight: 600; }
.pager { display: flex; justify-content: flex-end; padding: 12px 14px; border-top: 1px solid var(--ad-border); }
</style>
