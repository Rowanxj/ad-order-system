<template>
  <div v-loading="loading" class="ad-page">
    <!-- 头部：标题 + 按角色出现的操作 -->
    <PageHeader :title="order.title || '订单详情'" :subtitle="order.orderNo">
      <template v-if="auth.role === 'MANAGER'">
        <span v-if="order.status === 'IN_PROGRESS' && unconfirmedCount" class="hint-warn">
          {{ unconfirmedCount }} 项物料未确认
        </span>
        <el-button v-if="order.status === 'PENDING_CONFIRM'" type="primary" @click="onConfirm">
          确认订单
        </el-button>
        <el-button v-if="order.status === 'IN_PROGRESS'" type="success" @click="onComplete">
          订单交付
        </el-button>
      </template>

      <template v-if="auth.role === 'SALES'">
        <el-button v-if="['PENDING_CONFIRM', 'IN_PROGRESS'].includes(order.status)" @click="dlg.task = true">
          新增物料任务
        </el-button>
        <el-button v-if="['PENDING_CONFIRM', 'IN_PROGRESS'].includes(order.status)" @click="dlg.deadline = true">
          修改截止日期
        </el-button>
        <el-button v-if="order.status === 'PENDING_CONFIRM'" type="danger" plain @click="onCancel">
          取消订单
        </el-button>
      </template>
    </PageHeader>

    <!-- 下一步提示：告诉当前用户“这单现在卡在谁手上、该谁操作” -->
    <div v-if="nextStep" class="next-step" :class="{ mine: nextStep.role === auth.role }">
      <el-icon><InfoFilled /></el-icon>
      <span class="ns-text">{{ nextStep.text }}</span>
      <el-tag size="small" :type="nextStep.role === auth.role ? 'primary' : 'info'" effect="plain">
        轮到{{ ROLE[nextStep.role] }}
      </el-tag>
      <span v-if="nextStep.role === auth.role" class="ns-mine">该你操作 →</span>
    </div>

    <!-- 基本信息 -->
    <div class="ad-card">
      <div class="order-head">
        <StatusTag :status="order.status" :map="ORDER_STATUS" />
        <span class="order-deadline">
          整体截止
          <b :class="deadlineMeta(order.deadline, order.status).cls"
             :title="deadlineMeta(order.deadline, order.status).text">
            {{ fmtDate(order.deadline) }}
          </b>
        </span>
      </div>

      <el-descriptions :column="3" border size="small" class="order-desc">
        <el-descriptions-item label="客户">{{ order.customer?.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系人">
          {{ order.customer?.contactPerson || '-' }}（{{ order.customer?.contactPhone || '-' }}）
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ fmtTime(order.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="需求说明" :span="3">
          <span class="desc-text">{{ order.description || '（未填写）' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <el-row :gutter="16">
      <!-- 物料任务 -->
      <el-col :xs="24" :lg="15">
        <div class="ad-card ad-card--flush">
          <div class="card-head">
            <span class="ad-section-title">物料任务（{{ order.tasks?.length || 0 }} 项）</span>
            <span class="ad-muted" v-if="auth.role === 'MANAGER' && order.status === 'IN_PROGRESS'">
              待分派的任务点右侧「分派」
            </span>
          </div>

          <el-table :data="order.tasks || []" size="small">
            <el-table-column prop="name" label="物料名称" min-width="130" show-overflow-tooltip />
            <el-table-column label="类型" width="88" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" type="info">
                  {{ MATERIAL_TYPE[row.materialType] || row.materialType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="规格" width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="ad-mono">{{ row.spec || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="quantity" label="数量" width="62" align="center" />
            <el-table-column label="截止" width="106" align="center">
              <template #default="{ row }">{{ fmtDate(row.deadline) }}</template>
            </el-table-column>
            <el-table-column label="设计" width="96" align="center">
              <template #default="{ row }">
                <span :class="{ 'ad-muted': !row.assigneeName }">{{ row.assigneeName || '未分派' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="108" align="center">
              <template #default="{ row }">
                <StatusTag :status="row.status" :map="TASK_STATUS"
                           :text="taskStatusText(row.status, row.assigneeId)" />
              </template>
            </el-table-column>
            <el-table-column label="版本" width="60" align="center">
              <template #default="{ row }"><span class="ad-mono">{{ row.versionCount }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="128" fixed="right" align="center">
              <template #default="{ row }">
                <el-button v-if="canAssign(row)" link type="primary" @click="openAssign(row)">
                  {{ row.assigneeName ? '改派' : '分派' }}
                </el-button>
                <el-button link type="primary" @click="$router.push(`/tasks/${row.id}`)">详情</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyBlock description="还没有物料任务" />
            </template>
          </el-table>
        </div>
      </el-col>

      <!-- 操作历史 -->
      <el-col :xs="24" :lg="9">
        <div class="ad-card">
          <div class="ad-section-title">操作历史</div>
          <el-timeline v-if="order.logs?.length" class="history">
            <el-timeline-item v-for="log in order.logs" :key="log.id"
                              :timestamp="fmtTime(log.createdAt)" placement="top"
                              :type="log.action.includes('CONFIRM') ? 'success' : ''"
                              :hollow="!log.action.includes('CONFIRM')">
              <div class="log-title">
                {{ log.actionText }}
                <el-tag size="small" type="info" effect="plain">
                  {{ ROLE[log.operatorRole] || log.operatorRole }} · {{ log.operatorName }}
                </el-tag>
              </div>
              <div v-if="log.detail" class="log-detail">{{ log.detail }}</div>
            </el-timeline-item>
          </el-timeline>
          <EmptyBlock v-else description="还没有操作记录" />
        </div>
      </el-col>
    </el-row>

    <!-- 分派 / 改派 -->
    <el-dialog v-model="dlg.assign" :title="assignRow.assigneeName ? '改派任务' : '分派任务'" width="420px">
      <el-alert type="info" :closable="false" style="margin-bottom: 14px"
                :title="`物料：${assignRow.name}`" />
      <el-select v-model="assignForm.assigneeId" placeholder="选择设计制作人员" style="width: 100%" filterable>
        <el-option v-for="d in designers" :key="d.id" :label="`${d.realName}（${d.username}）`" :value="d.id" />
      </el-select>
      <div class="ad-muted" style="margin-top: 10px">只有“待分派”状态可以分派或改派，开始设计后不可改派。</div>
      <template #footer>
        <el-button @click="dlg.assign = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="saveAssign">确定</el-button>
      </template>
    </el-dialog>

    <!-- 修改整体截止日期 -->
    <el-dialog v-model="dlg.deadline" title="修改整体截止日期" width="380px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="订单确认后修改截止日期会留下变更记录（原值 → 新值）" />
      <el-date-picker v-model="newDeadline" type="date" value-format="YYYY-MM-DD"
                      :disabled-date="disabledPast" style="width: 100%" />
      <template #footer>
        <el-button @click="dlg.deadline = false">取消</el-button>
        <el-button type="primary" @click="saveDeadline">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增物料任务 -->
    <el-dialog v-model="dlg.task" title="新增物料任务" width="660px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="订单已确认时新增物料会写入变更记录" />
      <el-form label-width="90px">
        <el-form-item label="物料类型" required>
          <el-select v-model="newTask.materialType" style="width: 200px">
            <el-option v-for="(v, k) in MATERIAL_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="物料名称" required>
          <el-input v-model="newTask.name" placeholder="如：开业主海报" style="width: 320px" maxlength="100" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="newTask.spec" placeholder="如 600x900mm / A4 / 展开1米" style="width: 320px" maxlength="100" />
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="newTask.quantity" :min="1" :max="9999" /></el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="newTask.deadline" value-format="YYYY-MM-DD"
                          :disabled-date="disabledTaskDate" />
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="newTask.usageDesc" maxlength="255" />
        </el-form-item>
        <el-form-item label="参考说明">
          <el-input v-model="newTask.referenceNote" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.task = false">取消</el-button>
        <el-button type="primary" @click="saveTask">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ORDER_STATUS, TASK_STATUS, MATERIAL_TYPE, ROLE, fmtDate, fmtTime, taskStatusText, deadlineMeta } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const route = useRoute()
const auth = useAuthStore()

const order = ref({})
const loading = ref(false)
const designers = ref([])
const assigning = ref(false)

const dlg = reactive({ deadline: false, task: false, assign: false })
const newDeadline = ref(null)
const newTask = reactive({
  materialType: '', name: '', spec: '', quantity: 1, deadline: null, usageDesc: '', referenceNote: ''
})

const assignRow = ref({})
const assignForm = reactive({ assigneeId: null })

/** 未确认的任务数：只用来在界面上提示，真正的拦截仍在后端 */
const unconfirmedCount = computed(() =>
  (order.value.tasks || []).filter(t => t.status !== 'CONFIRMED').length
)

/**
 * 下一步提示。
 * 只看「谁还没做」，不做业务拦截：真正的规则仍在后端。
 * 目的是让人一眼知道这单卡在谁手上，而不是到处找按钮。
 */
const nextStep = computed(() => {
  const o = order.value
  if (!o.status || o.status === 'COMPLETED' || o.status === 'CANCELLED') return null

  const tasks = o.tasks || []

  if (o.status === 'PENDING_CONFIRM') {
    return { role: 'MANAGER', text: '订单待确认：需业务负责人核对物料清单与截止时间，确认后才进入生产' }
  }

  const count = st => tasks.filter(t => t.status === st).length
  const confirmed = count('CONFIRMED')
  const pendingConfirm = count('PENDING_CONFIRM')
  const revision = count('REVISION')
  const unassigned = tasks.filter(t => t.status === 'PENDING' && !t.assigneeId).length
  const notStarted = tasks.filter(t => t.status === 'PENDING' && t.assigneeId).length
  const designing = count('DESIGNING')

  if (tasks.length && confirmed === tasks.length) {
    return { role: 'MANAGER', text: '全部物料已确认，业务负责人可以交付整笔订单' }
  }
  if (pendingConfirm) {
    return { role: 'SALES', text: `有 ${pendingConfirm} 项物料待客户确认：客户经理进任务详情记录「客户已确认」或「修改意见」` }
  }
  if (unassigned) {
    return { role: 'MANAGER', text: `有 ${unassigned} 项物料待分派：业务负责人在任务行点「分派」选设计人员` }
  }
  if (revision) {
    return { role: 'DESIGNER', text: `有 ${revision} 项物料在修改中：等待设计人员上传新版本` }
  }
  if (notStarted) {
    return { role: 'DESIGNER', text: `有 ${notStarted} 项物料已分派：等待设计人员开始设计` }
  }
  if (designing) {
    return { role: 'DESIGNER', text: `有 ${designing} 项物料设计中：设计人员上传设计稿后进入待客户确认` }
  }
  return null
})

function isDueSoon(deadline, status) {
  if (!deadline || status === 'COMPLETED' || status === 'CANCELLED') return false
  return new Date(deadline).getTime() - Date.now() < 3 * 24 * 3600 * 1000
}

/** 只有负责人、且任务处于“待分派”时才可分派/改派（与后端的 from 状态一致） */
function canAssign(row) {
  return auth.role === 'MANAGER' && row.status === 'PENDING'
}

async function load() {
  loading.value = true
  try {
    order.value = await api.orderDetail(route.params.id)
  } finally {
    loading.value = false
  }
}

function openAssign(row) {
  assignRow.value = row
  assignForm.assigneeId = row.assigneeId || null
  dlg.assign = true
}

async function saveAssign() {
  if (!assignForm.assigneeId) return ElMessage.warning('请选择设计制作人员')
  assigning.value = true
  try {
    await api.assignTask(assignRow.value.id, assignForm.assigneeId)
    ElMessage.success('分派成功')
    dlg.assign = false
    load()
  } finally {
    assigning.value = false
  }
}

async function onConfirm() {
  await ElMessageBox.confirm(
    '确认物料清单与截止时间后将进入进行中，原要求不可直接覆盖，后续修改需留变更记录。', '确认订单？')
  await api.confirmOrder(order.value.id)
  ElMessage.success('订单已确认，进入进行中')
  load()
}

async function onCancel() {
  await ElMessageBox.confirm('仅待确认订单可以取消，确认取消？', '取消订单')
  await api.cancelOrder(order.value.id)
  ElMessage.success('订单已取消')
  load()
}

/**
 * 交付：这里不做“全部已确认”的前端判断。
 * 只要订单在进行中就允许点击，让后端返回 409 并说明还差几项——
 * 否则界面把按钮藏起来，用户永远看不到那条错误原因，前后端规则还会各写一份。
 */
async function onComplete() {
  if (unconfirmedCount.value > 0) {
    await ElMessageBox.confirm(
      `还有 ${unconfirmedCount.value} 项物料任务未确认，整笔订单不能交付。仍要尝试提交吗？`,
      '还不能交付', { confirmButtonText: '仍然提交', cancelButtonText: '知道了', type: 'warning' })
  } else {
    await ElMessageBox.confirm('订单内全部物料已确认，执行交付？', '订单交付')
  }
  await api.completeOrder(order.value.id)
  ElMessage.success('订单已交付')
  load()
}

async function saveDeadline() {
  if (!newDeadline.value) return ElMessage.warning('请选择日期')
  if (parseDate(newDeadline.value) < startOfToday()) {
    return ElMessage.warning('整体截止日期不能早于今天')
  }
  await api.updateOrderDeadline(order.value.id, newDeadline.value)
  ElMessage.success('截止日期已更新')
  dlg.deadline = false
  load()
}

async function saveTask() {
  if (!newTask.materialType || !newTask.name.trim()) return ElMessage.warning('请选择类型并填写物料名称')
  if (newTask.deadline && parseDate(newTask.deadline) < startOfToday()) {
    return ElMessage.warning('物料截止日期不能早于今天')
  }
  if (newTask.deadline && order.value.deadline
      && parseDate(newTask.deadline) > parseDate(order.value.deadline)) {
    return ElMessage.warning('物料截止日期不能晚于订单整体截止日期')
  }
  await api.addTask(order.value.id, { ...newTask })
  ElMessage.success('物料任务已添加')
  dlg.task = false
  Object.assign(newTask, {
    materialType: '', name: '', spec: '', quantity: 1, deadline: null, usageDesc: '', referenceNote: ''
  })
  load()
}

function startOfToday() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d
}

function parseDate(v) {
  const [y, m, d] = String(v).slice(0, 10).split('-').map(Number)
  return new Date(y, m - 1, d)
}

function disabledPast(d) {
  return d < startOfToday()
}

function disabledTaskDate(d) {
  if (disabledPast(d)) return true
  return !!order.value.deadline && d > parseDate(order.value.deadline)
}

onMounted(() => {
  load()
  if (auth.role === 'MANAGER') {
    api.designers().then(list => (designers.value = list)).catch(() => {})
  }
})
</script>

<style scoped>
.hint-warn {
  font-size: 12px; color: #b45309;
  background: #fffbeb; border: 1px solid #fde68a;
  padding: 4px 10px; border-radius: 999px;
  white-space: nowrap;
}
.next-step {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 12px 18px;
  border-radius: var(--ad-radius);
  background: #f7f9fd;
  border: 1px solid var(--ad-border);
  color: var(--ad-text-secondary);
  font-size: 13px;
}
.next-step.mine {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  color: var(--ad-text);
}
.ns-text { flex: 1; min-width: 240px; }
.ns-mine { font-weight: 600; color: var(--ad-primary); white-space: nowrap; }
.order-head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.order-deadline { font-size: 13px; color: var(--ad-text-secondary); }
.due-soon { color: #d97706; }
.order-desc :deep(.el-descriptions__label) { width: 90px; color: var(--ad-text-secondary); }
.desc-text { white-space: pre-wrap; line-height: 1.7; }
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px 10px;
}
.card-head .ad-section-title { margin-bottom: 0; }
.history { max-height: 540px; overflow-y: auto; padding-left: 2px; }
.log-title { font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.log-detail { font-size: 12px; color: var(--ad-text-secondary); margin-top: 4px; white-space: pre-wrap; }
</style>
