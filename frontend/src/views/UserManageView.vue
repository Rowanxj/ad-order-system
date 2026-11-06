<template>
  <div class="ad-page">
    <PageHeader title="账号管理" :subtitle="`共 ${rows.length} 个账号，待启用 ${pendingCount} 个`">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>&nbsp;新增账号
      </el-button>
    </PageHeader>

    <div class="ad-card ad-card--flush">
      <el-table :data="rows" v-loading="loading" row-key="id">
        <el-table-column prop="username" label="账号" width="150">
          <template #default="{ row }"><span class="ad-mono">{{ row.username }}</span></template>
        </el-table-column>
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ ROLE[row.role] || row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta(row.status).type">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="140">
          <template #default="{ row }"><span class="ad-mono">{{ row.phone || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }"><span class="ad-muted">{{ fmtTime(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" min-width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 1" link type="success" @click="setStatus(row, 1)">启用</el-button>
            <el-button v-else link type="warning" :disabled="row.id === auth.user?.id" @click="setStatus(row, 0)">停用</el-button>
            <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyBlock description="还没有账号" />
        </template>
      </el-table>
    </div>

    <!-- 新增账号 -->
    <el-dialog v-model="dlg.create" title="新增账号" width="480px" destroy-on-close @closed="resetCreate">
      <el-form ref="createRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="字母开头，3–20 位字母/数字/下划线" maxlength="20" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" maxlength="50" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="form.role">
            <el-radio-button value="SALES">客户经理</el-radio-button>
            <el-radio-button value="MANAGER">业务负责人</el-radio-button>
            <el-radio-button value="DESIGNER">设计制作人员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="form.password" placeholder="6–32 位" maxlength="32" show-password />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" maxlength="20" placeholder="选填" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" style="margin-top: 4px"
                title="角色就是权限：客户经理不能确认订单，设计人员看不到别人的任务。建号后请把账号密码线下告知本人。" />
      <template #footer>
        <el-button @click="dlg.create = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="dlg.reset" title="重置密码" width="400px" destroy-on-close @closed="resetPwd">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                :title="`将重置【${resetTarget.realName || ''}】的密码`" />
      <el-input v-model="pwd" placeholder="新密码（6–32 位）" show-password maxlength="32" />
      <div class="ad-muted" style="margin-top: 10px">用于用户忘记密码时重置；重置后请线下告知本人。</div>
      <template #footer>
        <el-button @click="dlg.reset = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveReset">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ROLE, fmtTime } from '@/utils/constants'
import PageHeader from '@/components/PageHeader.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'

const auth = useAuthStore()

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const dlg = reactive({ create: false, reset: false })
const createRef = ref(null)

const form = reactive({ username: '', realName: '', role: 'DESIGNER', password: '', phone: '' })
const resetTarget = ref({})
const pwd = ref('')

const rules = {
  username: [
    { required: true, message: '请填写账号', trigger: 'blur' },
    { pattern: /^[A-Za-z][A-Za-z0-9_]{2,19}$/, message: '字母开头，3–20 位字母/数字/下划线', trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [
    { required: true, message: '请填写初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6–32 位', trigger: 'blur' }
  ]
}

const pendingCount = computed(() => rows.value.filter(r => r.status === 2).length)

/** 状态：1 启用 / 0 停用 / 2 待启用 */
function statusMeta(status) {
  if (status === 1) return { label: '已启用', type: 'success' }
  if (status === 2) return { label: '待启用', type: 'warning' }
  return { label: '已停用', type: 'info' }
}

async function load() {
  loading.value = true
  try {
    rows.value = await api.users()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dlg.create = true
}

function resetCreate() {
  Object.assign(form, { username: '', realName: '', role: 'DESIGNER', password: '', phone: '' })
  createRef.value?.clearValidate()
}

async function saveCreate() {
  const ok = await createRef.value?.validate().catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await api.createUser({ ...form })
    ElMessage.success(`账号 ${form.username} 已创建`)
    dlg.create = false
    load()
  } finally {
    saving.value = false
  }
}

async function setStatus(row, status) {
  const action = status === 1 ? '启用' : '停用'
  await ElMessageBox.confirm(
    status === 1
      ? `启用后【${row.realName}】即可登录系统。`
      : `停用后【${row.realName}】将无法登录，但历史操作留痕会保留姓名与角色。`,
    `${action}账号`)
  await api.changeUserStatus(row.id, status)
  ElMessage.success(`已${action}`)
  load()
}

function openReset(row) {
  resetTarget.value = row
  pwd.value = ''
  dlg.reset = true
}

function resetPwd() {
  pwd.value = ''
}

async function saveReset() {
  if (!pwd.value || pwd.value.length < 6) return ElMessage.warning('新密码至少 6 位')
  saving.value = true
  try {
    await api.resetUserPassword(resetTarget.value.id, pwd.value)
    ElMessage.success('密码已重置')
    dlg.reset = false
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>
