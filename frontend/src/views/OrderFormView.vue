<template>
  <div class="ad-page">
    <PageHeader title="新建订单" subtitle="登记客户委托与物料明细，提交后进入「待确认」，等待业务负责人确认">
      <el-button @click="$router.back()">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交订单</el-button>
    </PageHeader>

    <el-row :gutter="16">
      <!-- 基础信息 -->
      <el-col :xs="24" :lg="9">
        <div class="ad-card">
          <div class="card-head"><span class="ad-section-title">订单信息</span></div>
          <el-form label-position="top">
            <el-form-item label="客户" required>
              <el-select v-model="form.customerId" filterable placeholder="选择客户" style="width: 100%">
                <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="订单标题" required>
              <el-input v-model="form.title" placeholder="如：鹿屿奶茶开业宣传物料" maxlength="100" show-word-limit />
            </el-form-item>
            <el-form-item label="整体截止日期">
              <el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD"
                              :disabled-date="disabledPast" placeholder="整单交付截止" style="width: 100%" />
            </el-form-item>
            <el-form-item label="整体需求说明">
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="500" show-word-limit
                        placeholder="客户的整体要求，如风格、色系、必须出现的元素" />
            </el-form-item>
          </el-form>
        </div>
      </el-col>

      <!-- 物料明细 -->
      <el-col :xs="24" :lg="15">
        <div class="ad-card">
          <div class="card-head">
            <span class="ad-section-title">物料清单（{{ form.tasks.length }} 项 · 至少一项）</span>
            <el-button type="primary" plain size="small" @click="addTask">
              <el-icon><Plus /></el-icon>&nbsp;添加物料
            </el-button>
          </div>

          <div v-for="(t, i) in form.tasks" :key="i" class="task-card">
            <div class="task-card-head">
              <span class="task-index">物料 {{ i + 1 }}</span>
              <el-button link type="danger" :disabled="form.tasks.length === 1"
                         @click="form.tasks.splice(i, 1)">删除</el-button>
            </div>

            <div class="task-grid">
              <el-form-item label="类型" required class="g-type">
                <el-select v-model="t.materialType" placeholder="选择类型">
                  <el-option v-for="(v, k) in MATERIAL_TYPE" :key="k" :label="v" :value="k" />
                </el-select>
              </el-form-item>
              <el-form-item label="名称" required class="g-name">
                <el-input v-model="t.name" placeholder="如：开业主海报" maxlength="100" />
              </el-form-item>
              <el-form-item label="数量" class="g-qty">
                <el-input-number v-model="t.quantity" :min="1" :max="9999" controls-position="right" style="width: 100%" />
              </el-form-item>
              <el-form-item label="规格" class="g-spec">
                <el-input v-model="t.spec" placeholder="600x900mm / A4" maxlength="100" />
              </el-form-item>
              <el-form-item label="物料截止" class="g-date">
                <el-date-picker v-model="t.deadline" type="date" value-format="YYYY-MM-DD"
                                :disabled-date="disabledTaskDate" placeholder="可空" style="width: 100%" />
              </el-form-item>
              <el-form-item label="用途说明" class="g-usage">
                <el-input v-model="t.usageDesc" placeholder="门店张贴 / 线上投放" maxlength="255" />
              </el-form-item>
              <el-form-item label="参考说明 / 客户原话" class="g-ref">
                <el-input v-model="t.referenceNote" placeholder="客户说“要暖色、要显档次”" maxlength="500" />
              </el-form-item>
            </div>
          </div>

          <el-empty v-if="!form.tasks.length" description="至少需要一项物料" :image-size="80" />
        </div>
      </el-col>
    </el-row>

    <!-- 底部操作条 -->
    <div class="form-footer">
      <span class="ad-muted">提交后订单进入「待确认」，此时仍可修改；业务负责人确认后修改将留下变更记录。</span>
      <div class="footer-actions">
        <el-button @click="$router.back()">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">提交订单</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { MATERIAL_TYPE } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()
const customers = ref([])
const saving = ref(false)

const form = reactive({
  customerId: null,
  title: '',
  description: '',
  deadline: null,
  tasks: [emptyTask()]
})

function emptyTask() {
  return { materialType: '', name: '', spec: '', quantity: 1, usageDesc: '', referenceNote: '', deadline: null }
}

function addTask() {
  form.tasks.push(emptyTask())
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
  return !!form.deadline && d > parseDate(form.deadline)
}

async function submit() {
  if (!form.customerId) return ElMessage.warning('请选择客户')
  if (!form.title.trim()) return ElMessage.warning('请填写订单标题')
  if (form.deadline && parseDate(form.deadline) < startOfToday()) {
    return ElMessage.warning('整体截止日期不能早于今天')
  }
  for (const [i, t] of form.tasks.entries()) {
    if (!t.materialType || !t.name.trim()) {
      return ElMessage.warning(`物料 ${i + 1} 请选择类型并填写名称`)
    }
    if (t.deadline && parseDate(t.deadline) < startOfToday()) {
      return ElMessage.warning(`物料 ${i + 1} 截止日期不能早于今天`)
    }
    if (t.deadline && form.deadline && parseDate(t.deadline) > parseDate(form.deadline)) {
      return ElMessage.warning(`物料 ${i + 1} 截止日期不能晚于订单整体截止日期`)
    }
  }
  saving.value = true
  try {
    const data = await api.createOrder({ ...form })
    ElMessage.success('订单已创建：' + data.orderNo)
    router.push('/orders/' + data.id)
  } finally {
    saving.value = false
  }
}

onMounted(() => api.customers().then(list => (customers.value = list)))
</script>

<style scoped>
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 14px;
}
.card-head .ad-section-title { margin-bottom: 0; }

.task-card {
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius-sm);
  background: #fbfcfe;
  padding: 12px 14px 2px;
  margin-bottom: 12px;
}
.task-card-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8px;
}
.task-index {
  font-size: 12px; font-weight: 600; color: var(--ad-primary);
  background: var(--el-color-primary-light-9);
  border-radius: 6px; padding: 2px 9px;
}

.task-grid {
  display: grid;
  grid-template-columns: 150px 1fr 120px 170px 160px;
  gap: 0 12px;
}
.task-grid :deep(.el-form-item) { margin-bottom: 12px; }
.g-usage { grid-column: span 2; }
.g-ref { grid-column: span 3; }

@media (max-width: 1280px) {
  .task-grid { grid-template-columns: 1fr 1fr; }
  .g-usage, .g-ref { grid-column: span 2; }
}

.form-footer {
  position: sticky; bottom: 0; z-index: 5;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  flex-wrap: wrap;
  padding: 12px 20px;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  box-shadow: 0 -2px 12px rgba(16, 24, 40, .06);
}
.footer-actions { display: flex; gap: 10px; }
</style>
