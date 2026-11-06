<template>
  <div class="ad-page">
    <PageHeader title="客户管理" :subtitle="`共 ${rows.length} 位客户`">
      <el-button v-if="auth.role === 'SALES'" type="primary" @click="openDialog">
        <el-icon><Plus /></el-icon>&nbsp;新增客户
      </el-button>
    </PageHeader>

    <div class="ad-card">
      <div class="ad-toolbar">
        <el-input v-model="keyword" placeholder="客户名称 / 联系人" clearable style="width: 240px"
                  @keyup.enter="load" @clear="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>
    </div>

    <div class="ad-card ad-card--flush">
      <el-table :data="rows" v-loading="loading" row-key="id">
        <el-table-column prop="name" label="客户名称" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="customer-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="contactPerson" label="联系人" width="110" />
        <el-table-column label="联系电话" width="140">
          <template #default="{ row }">
            <span class="ad-mono">{{ row.contactPhone || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="行业" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.industry" size="small" effect="plain" type="info">{{ row.industry }}</el-tag>
            <span v-else class="ad-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'ad-muted': !row.remark }">{{ row.remark || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="登记时间" width="160" align="center">
          <template #default="{ row }"><span class="ad-muted">{{ fmtTime(row.createdAt) }}</span></template>
        </el-table-column>
        <template #empty>
          <EmptyBlock :description="keyword ? '没有匹配的客户，换个关键词试试' : '还没有客户，先去登记第一位'" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dlg" title="新增客户" width="480px" destroy-on-close @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="客户名称" prop="name">
          <el-input v-model="form.name" placeholder="公司或店铺名称" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="对接人姓名" maxlength="50" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="选填" maxlength="20" />
        </el-form-item>
        <el-form-item label="所属行业" prop="industry">
          <el-input v-model="form.industry" placeholder="选填，如 餐饮 / 零售" maxlength="50" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { fmtTime } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const auth = useAuthStore()

const rows = ref([])
const keyword = ref('')
const loading = ref(false)
const saving = ref(false)
const dlg = ref(false)
const formRef = ref(null)

const form = reactive({ name: '', contactPerson: '', contactPhone: '', industry: '', remark: '' })

const rules = {
  name: [{ required: true, message: '请填写客户名称', trigger: 'blur' }],
  contactPerson: [{ required: true, message: '请填写联系人', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    rows.value = await api.customers(keyword.value || undefined)
  } finally {
    loading.value = false
  }
}

function onReset() {
  keyword.value = ''
  load()
}

function openDialog() {
  dlg.value = true
}

function resetForm() {
  Object.assign(form, { name: '', contactPerson: '', contactPhone: '', industry: '', remark: '' })
  formRef.value?.clearValidate()
}

async function save() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return

  saving.value = true
  try {
    await api.createCustomer({ ...form })
    ElMessage.success('客户已登记')
    dlg.value = false
    load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.customer-name { font-weight: 500; color: var(--ad-text); }
</style>
