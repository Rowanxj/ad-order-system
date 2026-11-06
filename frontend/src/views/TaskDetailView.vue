<template>
  <div v-loading="loading" class="ad-page">
    <PageHeader :title="task.name || '任务详情'"
                :subtitle="`${task.orderNo || ''} · ${task.orderTitle || ''}`">
      <el-button @click="$router.push(`/orders/${task.orderId}`)">
        <el-icon><Back /></el-icon>&nbsp;返回订单
      </el-button>

      <el-button v-if="isMyTask && task.status === 'PENDING'" type="primary" @click="onStart">
        开始设计
      </el-button>

      <template v-if="auth.role === 'SALES'">
        <el-button v-if="task.status === 'PENDING_CONFIRM'" type="success" @click="onConfirm">
          客户已确认当前版本
        </el-button>
        <el-button v-if="task.status === 'PENDING_CONFIRM'" type="warning" plain @click="dlg.feedback = true">
          记录客户修改意见
        </el-button>
        <el-button v-if="task.status !== 'CONFIRMED'" @click="dlg.deadline = true">修改截止日期</el-button>
      </template>
    </PageHeader>

    <!-- 客户经理常见困惑：为什么没有「记录意见」按钮。没按钮时必须说清原因 -->
    <el-alert v-if="auth.role === 'SALES' && task.status !== 'PENDING_CONFIRM'"
              type="info" :closable="false"
              :title="`当前状态是「${TASK_STATUS[task.status]?.label || task.status}」，还不能记录客户意见`"
              :description="statusHint" />

    <!-- 基本信息 -->
    <div class="ad-card">
      <div class="task-head">
        <StatusTag :status="task.status" :map="TASK_STATUS"
                   :text="taskStatusText(task.status, task.assigneeId)" />

        <el-tag v-if="task.assigneeName" size="small" effect="plain" type="info">
          设计：{{ task.assigneeName }}
        </el-tag>
        <el-tag v-else size="small" effect="plain" type="warning">未分派</el-tag>

        <span class="ad-muted">订单状态 {{ ORDER_STATUS[task.orderStatus]?.label || '-' }}</span>

        <span class="ad-muted">
          截止
          <b :class="deadlineMeta(task.deadline, task.orderStatus).cls"
             :title="deadlineMeta(task.deadline, task.orderStatus).text">
            {{ fmtDate(task.deadline) }}
          </b>
        </span>
      </div>

      <el-descriptions :column="3" border size="small" class="task-desc">
        <el-descriptions-item label="物料类型">
          {{ MATERIAL_TYPE[task.materialType] || task.materialType }}
        </el-descriptions-item>
        <el-descriptions-item label="规格">
          <span class="ad-mono">{{ task.spec || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="数量">{{ task.quantity }}</el-descriptions-item>
        <el-descriptions-item label="用途说明" :span="3">
          <span class="desc-text">{{ task.usageDesc || '（未填写）' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="参考说明 / 客户原话" :span="3">
          <span class="desc-text">{{ task.referenceNote || '（未填写）' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <el-row :gutter="16">
      <!-- 设计版本 -->
      <el-col :xs="24" :lg="14">
        <div class="ad-card">
          <div class="card-head">
            <span class="ad-section-title">设计版本（{{ versions.length }} 版 · 只追加不覆盖）</span>
          </div>

          <!-- 上传：设计人员本人，设计中/修改中 -->
          <div v-if="isMyTask && ['DESIGNING', 'REVISION'].includes(task.status)" class="upload-box">
            <el-upload :auto-upload="false" :limit="1" :on-change="f => (file = f.raw)" drag>
              <el-icon size="34"><UploadFilled /></el-icon>
              <div class="el-upload__text">拖拽或点击选择设计稿（图片 / PDF / 设计源文件，≤20MB）</div>
            </el-upload>
            <el-input v-model="uploadNote" style="margin-top: 10px"
                      placeholder="本版提交说明，如：按意见改为奶茶棕主色" maxlength="255" />
            <el-button type="primary" style="margin-top: 10px" :loading="uploading" @click="onUpload">
              提交第 {{ nextVersionNo }} 版（提交后进入待客户确认）
            </el-button>
          </div>

          <el-alert v-else-if="isMyTask && task.status === 'PENDING_CONFIRM'" type="info" :closable="false"
                    style="margin-bottom: 12px"
                    title="已提交，等待客户经理记录客户确认结果或修改意见" />

          <!-- 其他人看这个任务时，说清“现在轮到谁” -->
          <el-alert v-else-if="task.status === 'PENDING_CONFIRM' && auth.role !== 'SALES'" type="warning" :closable="false"
                    style="margin-bottom: 12px"
                    :title="auth.role === 'MANAGER'
                      ? '该物料待客户确认：需客户经理记录客户确认结果或修改意见后，才能继续推进'
                      : '该物料待客户确认：等待客户经理记录客户确认结果或修改意见'" />

          <el-timeline v-if="versions.length" class="version-line">
            <el-timeline-item v-for="v in versions" :key="v.id"
                              :timestamp="`V${v.versionNo} · ${fmtTime(v.uploadedAt)} · ${v.uploadedByName}`"
                              :type="v.versionNo === versions.length ? 'primary' : ''"
                              :hollow="v.versionNo !== versions.length" placement="top">
              <div class="ver-title">
                <span class="ver-badge">V{{ v.versionNo }}</span>
                <span class="ver-name">{{ v.fileName }}</span>
                <el-button link type="primary" size="small" @click="download(v)">下载</el-button>
              </div>
              <div class="ver-meta">
                {{ fmtSize(v.fileSize) }}<span v-if="v.note"> · {{ v.note }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
          <EmptyBlock v-else description="还没有上传任何版本" />
        </div>
      </el-col>

      <!-- 客户意见 -->
      <el-col :xs="24" :lg="10">
        <div class="ad-card">
          <div class="card-head">
            <span class="ad-section-title">客户修改意见（{{ feedbacks.length }} 轮）</span>
          </div>

          <div v-if="feedbacks.length" class="fb-list">
            <article v-for="f in feedbacks" :key="f.id" class="fb-item">
              <header class="fb-head">
                <span class="fb-round">第 {{ f.roundNo }} 轮</span>
                <span class="ad-muted">{{ f.recordedByName }} · {{ fmtTime(f.recordedAt) }}</span>
              </header>
              <p class="fb-content">{{ f.content }}</p>
            </article>
          </div>
          <EmptyBlock v-else description="客户还没有提出修改意见" />
        </div>
      </el-col>
    </el-row>

    <!-- 记录客户修改意见 -->
    <el-dialog v-model="dlg.feedback" title="记录客户修改意见" width="520px">
      <el-alert type="info" :closable="false" style="margin-bottom: 12px"
                title="记录后任务进入「修改中」，需设计人员提交新版本后才能再次确认" />
      <div class="fb-templates">
        <span class="ad-muted" style="margin-right: 6px">常用意见：</span>
        <el-tag v-for="t in feedbackTemplates" :key="t" class="fb-tpl" effect="plain" @click="feedbackContent = t">
          {{ t }}
        </el-tag>
      </div>
      <el-input v-model="feedbackContent" type="textarea" :rows="4" maxlength="500" show-word-limit
                placeholder="填写客户对当前版本的意见，尽量保留客户原话" />
      <template #footer>
        <el-button @click="dlg.feedback = false">取消</el-button>
        <el-button type="primary" @click="saveFeedback">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改截止日期 -->
    <el-dialog v-model="dlg.deadline" title="修改物料截止日期" width="380px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="订单确认后修改会留下变更记录（原值 → 新值）" />
      <el-date-picker v-model="newDeadline" type="date" value-format="YYYY-MM-DD"
                      :disabled-date="disabledTaskDate" style="width: 100%" />
      <template #footer>
        <el-button @click="dlg.deadline = false">取消</el-button>
        <el-button type="primary" @click="saveDeadline">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, downloadFile } from '@/api'
import { useAuthStore } from '@/stores/auth'
import {
  ORDER_STATUS, TASK_STATUS, MATERIAL_TYPE,
  fmtDate, fmtTime, fmtSize, taskStatusText, deadlineMeta
} from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const route = useRoute()
const auth = useAuthStore()
const task = ref({})
const versions = ref([])
const feedbacks = ref([])
const loading = ref(false)
const uploading = ref(false)
const dlg = reactive({ feedback: false, deadline: false })
const feedbackContent = ref('')

/** 常用意见模板：点一下填入，仍可自由编辑。给不熟悉写法的同事一个起点。 */
const feedbackTemplates = [
  '颜色与客户品牌色不一致，请调整为品牌主色',
  '文案需换成客户提供的版本，其余版面保持',
  '图片清晰度不足，请提供 300dpi 以上的源文件',
  '版面留白太少，希望更简洁、突出主标题',
  '尺寸需按门店实际门头尺寸调整'
]

const newDeadline = ref(null)
const uploadNote = ref('')
const file = ref(null)

const isMyTask = computed(() =>
  auth.role === 'DESIGNER' && task.value.assigneeId === auth.user?.id)

/**
 * 客户经理最困惑的地方：按钮只在「待客户确认」出现，其他状态是空的。
 * 这里把“为什么现在不能记”讲清楚，而不是让人以为功能坏了。
 */
const STATUS_HINT = {
  PENDING: '任务还没开始设计。等设计人员开始设计并上传设计稿后，才会进入「待客户确认」。',
  DESIGNING: '设计人员正在设计，尚未提交稿件。等他上传设计稿后才可记录客户意见。',
  REVISION: '已经记录过一轮客户意见，任务处于「修改中」，等设计人员上传新版本后可再次记录意见或确认。',
  CONFIRMED: '客户已确认完成，这个任务不需要再记录意见。'
}

const statusHint = computed(() => STATUS_HINT[task.value.status] || '')

const nextVersionNo = computed(() => versions.value.length + 1)

async function load() {
  loading.value = true
  try {
    const data = await api.taskDetail(route.params.id)
    task.value = data
    versions.value = data.versions || []
    feedbacks.value = data.feedbacks || []
  } finally {
    loading.value = false
  }
}

async function onStart() {
  await api.startTask(task.value.id)
  ElMessage.success('已开始设计')
  load()
}

async function onUpload() {
  if (!file.value) return ElMessage.warning('请先选择设计稿文件')
  const fd = new FormData()
  fd.append('file', file.value)
  if (uploadNote.value) fd.append('note', uploadNote.value)
  uploading.value = true
  try {
    const data = await api.uploadVersion(task.value.id, fd)
    ElMessage.success(`第 ${data.versionNo} 版已提交，等待客户确认`)
    file.value = null
    uploadNote.value = ''
    load()
  } finally {
    uploading.value = false
  }
}

async function onConfirm() {
  await ElMessageBox.confirm('确认客户已认可当前版本？确认后任务完成，不可回退。', '客户确认')
  await api.confirmTask(task.value.id)
  ElMessage.success('客户确认完成，任务已确认')
  load()
}

async function saveFeedback() {
  if (!feedbackContent.value.trim()) return ElMessage.warning('请填写意见内容')
  await api.recordFeedback(task.value.id, feedbackContent.value)
  ElMessage.success('修改意见已记录，任务进入修改中')
  dlg.feedback = false
  feedbackContent.value = ''
  load()
}

async function saveDeadline() {
  if (!newDeadline.value) return ElMessage.warning('请选择日期')
  if (parseDate(newDeadline.value) < startOfToday()) {
    return ElMessage.warning('物料截止日期不能早于今天')
  }
  if (task.value.orderDeadline && parseDate(newDeadline.value) > parseDate(task.value.orderDeadline)) {
    return ElMessage.warning('物料截止日期不能晚于订单整体截止日期')
  }
  await api.updateTaskDeadline(task.value.id, newDeadline.value)
  ElMessage.success('截止日期已更新')
  dlg.deadline = false
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

function disabledTaskDate(d) {
  if (d < startOfToday()) return true
  return !!task.value.orderDeadline && d > parseDate(task.value.orderDeadline)
}

async function download(v) {
  const resp = await downloadFile(v.filePath)
  const url = URL.createObjectURL(new Blob([resp.data]))
  const a = document.createElement('a')
  a.href = url
  a.download = v.fileName
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<style scoped>
.task-head {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-bottom: 14px;
}
.task-desc :deep(.el-descriptions__label) { width: 108px; color: var(--ad-text-secondary); }
.desc-text { white-space: pre-wrap; line-height: 1.7; }

.card-head { margin-bottom: 12px; }
.card-head .ad-section-title { margin-bottom: 0; }

.upload-box {
  background: #f8fafd;
  border: 1px dashed var(--el-color-primary-light-7);
  border-radius: var(--ad-radius-sm);
  padding: 14px;
  margin-bottom: 12px;
}

.version-line { padding-left: 2px; }
.ver-title { display: flex; align-items: center; gap: 8px; }
.ver-badge {
  font-size: 11px; font-weight: 600; color: var(--ad-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px; padding: 1px 7px;
  font-family: Consolas, monospace;
}
.ver-name { font-size: 14px; font-weight: 600; color: var(--ad-text); }
.ver-meta { font-size: 12px; color: var(--ad-text-muted); margin-top: 3px; }

.fb-list { display: flex; flex-direction: column; }
.fb-item { padding: 12px 0; border-bottom: 1px dashed var(--ad-border); }
.fb-item:last-child { border-bottom: none; }
.fb-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.fb-round {
  font-size: 11px; font-weight: 600; color: #b45309;
  background: #fffbeb; border: 1px solid #fde68a;
  border-radius: 999px; padding: 1px 8px;
}
.fb-content {
  margin: 0; font-size: 13px; line-height: 1.7;
  color: var(--ad-text); white-space: pre-wrap;
}

.fb-templates { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-bottom: 10px; }
.fb-tpl { cursor: pointer; }
.fb-tpl:hover { color: var(--ad-primary); border-color: var(--el-color-primary-light-5); }
</style>
