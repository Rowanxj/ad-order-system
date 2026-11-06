<template>
  <div class="ad-page">
    <PageHeader title="任务工作台" :subtitle="`共 ${total} 项任务，当前第 ${page} 页`">
      <el-button @click="$router.push('/orders')">
        <el-icon><Document /></el-icon>&nbsp;订单管理
      </el-button>
    </PageHeader>

    <!-- 筛选 -->
    <div class="ad-card">
      <div class="ad-toolbar">
        <el-input v-model="query.keyword" placeholder="任务 / 订单号 / 标题 / 客户" clearable
                  style="width: 250px" @keyup.enter="onSearch" @clear="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select v-model="query.status" placeholder="任务状态" clearable style="width: 150px" @change="onSearch">
          <el-option v-for="(v, k) in TASK_STATUS" :key="k"
                     :label="k === 'PENDING' ? '待分派 / 待开始' : v.label" :value="k" />
        </el-select>

        <el-select v-model="query.assigneeId" placeholder="分派人" clearable filterable
                   style="width: 150px" @change="onSearch">
          <el-option v-for="d in designers" :key="d.id" :label="d.realName" :value="d.id" />
        </el-select>

        <el-select v-model="query.orderId" placeholder="订单" clearable filterable
                   style="width: 240px" @change="onSearch">
          <el-option v-for="o in orders" :key="o.id" :label="`${o.orderNo} · ${o.title}`" :value="o.id" />
        </el-select>

        <el-checkbox v-model="query.overdue" @change="onSearch">仅看逾期</el-checkbox>
        <el-checkbox v-model="query.unassigned" @change="onSearch">仅看未分派</el-checkbox>

        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>

      <div class="scope-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>负责人视角：包含全部订单下的任务。逾期口径为「任务未确认 + 订单未结束 + 截止日期早于今天」。</span>
      </div>
    </div>

    <!-- 任务表 -->
    <div class="ad-card ad-card--flush">
      <el-table :data="rows" v-loading="loading" row-key="id">
        <el-table-column label="任务" min-width="200">
          <template #default="{ row }">
            <div class="task-cell">
              <span class="task-name">{{ row.name }}</span>
              <span class="task-meta">
                {{ MATERIAL_TYPE[row.materialType] || row.materialType }}
                <template v-if="row.spec"> · {{ row.spec }}</template>
                <template v-if="row.quantity"> × {{ row.quantity }}</template>
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="所属订单" min-width="220">
          <template #default="{ row }">
            <div class="order-cell">
              <span class="order-no ad-mono" @click="$router.push(`/orders/${row.orderId}`)">
                {{ row.orderNo }}
              </span>
              <span class="order-title">{{ row.orderTitle }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="customerName" label="客户" min-width="140" show-overflow-tooltip />

        <el-table-column label="分派人" width="120">
          <template #default="{ row }">
            <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
            <el-tag v-else size="small" type="warning" effect="plain">未分派</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="124">
          <template #default="{ row }">
            <StatusTag :status="row.status" :map="TASK_STATUS"
                       :text="taskStatusText(row.status, row.assigneeId)" />
          </template>
        </el-table-column>

        <el-table-column label="截止日期" width="152" align="center">
          <template #default="{ row }">
            <div class="due-cell">
              <span class="ad-mono" :class="dueMeta(row).cls">{{ fmtDate(row.deadline) }}</span>
              <span v-if="dueMeta(row).text" class="due-text" :class="dueMeta(row).cls">
                {{ dueMeta(row).text }}
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="版本 / 意见" width="110" align="center">
          <template #default="{ row }">
            <span class="ad-muted">V{{ row.versionCount || 0 }} · {{ row.feedbackCount || 0 }} 轮</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/tasks/${row.id}`)">详情</el-button>
            <el-button v-if="canAssign(row)" link type="warning" @click="openAssign(row)">
              {{ row.assigneeId ? '改派' : '分派' }}
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <EmptyBlock :description="hasFilter ? '没有符合筛选条件的任务，试试清空条件' : '还没有任务'" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[10, 20, 50]"
                       :total="total" background layout="total, sizes, prev, pager, next, jumper"
                       @current-change="load" @size-change="onSizeChange" />
      </div>
    </div>

    <!-- 分派 / 改派 -->
    <el-dialog v-model="assignDlg.visible"
               :title="assignDlg.task?.assigneeId ? '改派任务' : '分派任务'" width="420px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                :title="`任务：${assignDlg.task?.name || ''}`" />
      <el-select v-model="assignDlg.assigneeId" placeholder="选择设计制作人员" filterable style="width: 100%">
        <el-option v-for="d in designers" :key="d.id" :label="d.realName" :value="d.id" />
      </el-select>
      <template #footer>
        <el-button @click="assignDlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="assignSaving" @click="saveAssign">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { TASK_STATUS, MATERIAL_TYPE, fmtDate, taskStatusText, deadlineMeta } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const designers = ref([])
const orders = ref([])

const query = reactive({
  keyword: '',
  status: '',
  assigneeId: null,
  orderId: null,
  overdue: false,
  unassigned: false
})

const hasFilter = computed(() => Boolean(
  query.keyword || query.status || query.assigneeId || query.orderId || query.overdue || query.unassigned
))

const assignDlg = reactive({ visible: false, task: null, assigneeId: null })
const assignSaving = ref(false)

/** 已确认任务不再显示“逾期”，已结束订单的提示也与截止日期分开 */
function dueMeta(row) {
  if (row.status === 'CONFIRMED') return { cls: '', text: '已完成' }
  if (row.orderStatus === 'COMPLETED') return { cls: '', text: '订单已交付' }
  if (row.orderStatus === 'CANCELLED') return { cls: '', text: '订单已取消' }
  return deadlineMeta(row.deadline, row.orderStatus)
}

function canAssign(row) {
  return row.status === 'PENDING' && !['COMPLETED', 'CANCELLED'].includes(row.orderStatus)
}

async function load() {
  loading.value = true
  try {
    const data = await api.tasks({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      assigneeId: query.assigneeId || undefined,
      orderId: query.orderId || undefined,
      overdue: query.overdue || undefined,
      unassigned: query.unassigned || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = data?.list || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    designers.value = await api.designers()
  } catch {
    designers.value = []
  }
  try {
    const data = await api.orders({ page: 1, size: 100 })
    orders.value = data?.list || []
  } catch {
    orders.value = []
  }
}

function onSearch() {
  page.value = 1
  load()
}

function onReset() {
  Object.assign(query, {
    keyword: '',
    status: '',
    assigneeId: null,
    orderId: null,
    overdue: false,
    unassigned: false
  })
  page.value = 1
  load()
}

function onSizeChange() {
  page.value = 1
  load()
}

function openAssign(row) {
  assignDlg.task = row
  assignDlg.assigneeId = row.assigneeId || null
  assignDlg.visible = true
}

async function saveAssign() {
  if (!assignDlg.assigneeId) return ElMessage.warning('请选择设计制作人员')
  assignSaving.value = true
  try {
    await api.assignTask(assignDlg.task.id, assignDlg.assigneeId)
    ElMessage.success(`【${assignDlg.task.name}】已分派`)
    assignDlg.visible = false
    load()
  } finally {
    assignSaving.value = false
  }
}

onMounted(() => {
  load()
  loadOptions()
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

.task-cell { display: flex; flex-direction: column; gap: 3px; }
.task-name { font-size: 14px; font-weight: 600; color: var(--ad-text); line-height: 1.35; }
.task-meta { font-size: 12px; color: var(--ad-text-muted); }

.order-cell { display: flex; flex-direction: column; gap: 2px; }
.order-no { color: var(--ad-primary); cursor: pointer; font-size: 12px; }
.order-no:hover { text-decoration: underline; }
.order-title {
  font-size: 12px; color: var(--ad-text-secondary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.due-cell { display: flex; flex-direction: column; gap: 1px; line-height: 1.35; }
.due-text { font-size: 11px; }

.pager { display: flex; justify-content: flex-end; padding: 14px 18px; border-top: 1px solid var(--ad-border); }
</style>
