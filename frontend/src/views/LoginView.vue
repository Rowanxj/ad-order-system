<template>
  <div class="login-page">
    <!-- 左：项目介绍 -->
    <section class="brand-panel">
      <div class="brand-top">
        <div class="brand-logo">物</div>
        <div class="brand-id">
          <span class="brand-name">宣传物料订单管理系统</span>
          <span class="brand-en">MATERIAL ORDER SYSTEM</span>
        </div>
      </div>

      <div class="brand-main">
        <h1 class="brand-headline">每一笔物料订单<br />都能追到人、追到版本</h1>
        <p class="brand-desc">
          广告制作的订单要跨三个岗位、经过多轮返工。这个系统把「谁改的、客户确认的是第几版、现在卡在哪一步」
          变成随时可查的记录。
        </p>

        <ul class="brand-points">
          <li>
            <el-icon><CircleCheck /></el-icon>
            <div class="point-text">
              <b>状态机驱动</b>
              <span>订单 4 态、任务 5 态，跳步与重复操作直接被 409 拦下</span>
            </div>
          </li>
          <li>
            <el-icon><Lock /></el-icon>
            <div class="point-text">
              <b>双闸权限</b>
              <span>入口按角色拦，服务层再按数据归属拦；设计人员看不到别人的任务</span>
            </div>
          </li>
          <li>
            <el-icon><Tickets /></el-icon>
            <div class="point-text">
              <b>全程留痕</b>
              <span>12 类关键动作写入操作历史，改期必须留下「原值 → 新值」</span>
            </div>
          </li>
        </ul>

        <div class="brand-stats">
          <div class="stat"><b>3</b><span>岗位角色</span></div>
          <div class="stat"><b>4</b><span>层业务对象</span></div>
          <div class="stat"><b>9</b><span>状态节点</span></div>
          <div class="stat"><b>12</b><span>留痕动作</span></div>
        </div>
      </div>

      <div class="brand-foot">
        <div class="tech-tags">
          <span>Spring Boot 3</span>
          <span>MySQL 8</span>
          <span>MyBatis</span>
          <span>Vue 3</span>
          <span>Element Plus</span>
        </div>
        <div class="foot-line">AI 全栈课程项目 · 常规赛道选题一 · 内部协作演示环境</div>
      </div>
    </section>

    <!-- 右：登录表单 -->
    <section class="form-panel">
      <div class="login-card">
        <h2 class="login-title">登录</h2>
        <p class="login-sub">先选择你的身份，再输入账号密码</p>

        <!-- 身份选择：账号角色必须与所选身份一致，否则后端拒绝登录 -->
        <div class="role-switch">
          <button v-for="r in roles" :key="r.key" type="button"
                  class="role-item" :class="{ active: role === r.key }" @click="pickRole(r.key)">
            <el-icon :size="16"><component :is="r.icon" /></el-icon>
            <span>{{ r.label }}</span>
          </button>
        </div>

        <el-form @submit.prevent="onLogin">
          <el-form-item>
            <el-input v-model="username" placeholder="账号" size="large" autocomplete="username">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input v-model="password" type="password" placeholder="密码" size="large"
                      show-password autocomplete="current-password">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button type="primary" size="large" class="login-btn" :loading="loading" native-type="submit">
            登 录
          </el-button>
        </el-form>

        <div class="demo-title">演示账号（密码均为 123456）</div>
        <div class="demo-accounts">
          <button v-for="acc in visibleAccounts" :key="acc.username" type="button" class="demo-chip"
                  :class="{ active: username === acc.username }" @click="quickFill(acc)">
            <span class="demo-role">{{ acc.label }}</span>
            <span class="demo-user">{{ acc.username }}</span>
          </button>
        </div>

        <div class="register-line">
          还没有账号？<a @click="dlgRegister = true">注册设计人员账号</a>
        </div>
      </div>

      <!-- 自助注册：只能注册为设计制作人员，注册后待负责人启用 -->
      <el-dialog v-model="dlgRegister" title="注册设计人员账号" width="440px" append-to-body>
        <el-alert type="info" :closable="false" style="margin-bottom: 14px"
                  title="自助注册只能注册“设计制作人员”，注册后需业务负责人启用才能登录。客户经理与负责人账号由负责人在「账号管理」中创建。" />
        <el-form label-width="82px">
          <el-form-item label="账号" required>
            <el-input v-model="reg.username" placeholder="字母开头，3–20 位字母/数字/下划线" maxlength="20" />
          </el-form-item>
          <el-form-item label="姓名" required>
            <el-input v-model="reg.realName" maxlength="50" />
          </el-form-item>
          <el-form-item label="密码" required>
            <el-input v-model="reg.password" show-password placeholder="6–32 位" maxlength="32" />
          </el-form-item>
          <el-form-item label="确认密码" required>
            <el-input v-model="reg.confirm" show-password maxlength="32" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="reg.phone" maxlength="20" placeholder="选填" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dlgRegister = false">取消</el-button>
          <el-button type="primary" :loading="registering" @click="doRegister">提交注册</el-button>
        </template>
      </el-dialog>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)

// 自助注册
const dlgRegister = ref(false)
const registering = ref(false)
const reg = reactive({ username: '', realName: '', password: '', confirm: '', phone: '' })

async function doRegister() {
  if (!reg.username || !reg.realName || !reg.password) {
    return ElMessage.warning('账号、姓名、密码都要填')
  }
  if (!/^[A-Za-z][A-Za-z0-9_]{2,19}$/.test(reg.username)) {
    return ElMessage.warning('账号需以字母开头，3–20 位字母、数字或下划线')
  }
  if (reg.password.length < 6) return ElMessage.warning('密码至少 6 位')
  if (reg.password !== reg.confirm) return ElMessage.warning('两次输入的密码不一致')

  registering.value = true
  try {
    const data = await api.register({
      username: reg.username.trim(),
      realName: reg.realName.trim(),
      password: reg.password,
      phone: reg.phone
    })
    ElMessage.success(data?.message || '注册成功，等待业务负责人启用')
    dlgRegister.value = false
    username.value = reg.username.trim()
    password.value = ''
    Object.assign(reg, { username: '', realName: '', password: '', confirm: '', phone: '' })
  } finally {
    registering.value = false
  }
}

/** 登录身份：先选身份、再输账号，后端会校验两者一致 */
const role = ref('SALES')
const roles = [
  { key: 'SALES', label: '客户经理', icon: 'User' },
  { key: 'MANAGER', label: '业务负责人', icon: 'Avatar' },
  { key: 'DESIGNER', label: '设计制作人员', icon: 'Brush' }
]

function pickRole(key) {
  if (role.value === key) return
  role.value = key
  username.value = ''
  password.value = ''
}

const demoAccounts = [
  { label: '客户经理', role: 'SALES', username: 'sales' },
  { label: '业务负责人', role: 'MANAGER', username: 'manager' },
  { label: '设计·李文静', role: 'DESIGNER', username: 'designer1' },
  { label: '设计·陈涛', role: 'DESIGNER', username: 'designer2' }
]

/** 只显示当前身份对应的演示账号，避免拿错账号登录 */
const visibleAccounts = computed(() => demoAccounts.filter(a => a.role === role.value))

function quickFill(acc) {
  username.value = acc.username
  password.value = '123456'
}

async function onLogin() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value, password.value, role.value)
    ElMessage.success('欢迎，' + auth.user.realName)
    router.push(route.query.redirect || '/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { height: 100vh; display: flex; background: var(--ad-bg); }

/* ---------- 左：品牌 ---------- */
.brand-panel {
  flex: 1 1 58%;
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  color: #fff;
  background: linear-gradient(150deg, #0f172a 0%, #172554 45%, #1d4ed8 100%);
  overflow: hidden;
}
.brand-panel::after {
  content: '';
  position: absolute;
  right: -120px; bottom: -140px;
  width: 420px; height: 420px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(96, 165, 250, .35), transparent 70%);
}
.brand-top { display: flex; align-items: center; gap: 12px; z-index: 1; }
.brand-logo {
  width: 40px; height: 40px; border-radius: 12px;
  background: rgba(255, 255, 255, .14);
  border: 1px solid rgba(255, 255, 255, .18);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 700;
}
.brand-name { font-size: 15px; font-weight: 600; letter-spacing: .5px; }

/* ---------- 项目介绍（左栏） ---------- */
.brand-id { display: flex; flex-direction: column; gap: 2px; }
.brand-en {
  font-size: 10px; letter-spacing: 2px;
  color: rgba(191, 210, 255, .45);
}

.brand-points { margin-bottom: 26px; }
.brand-points li { align-items: flex-start; }
.point-text { display: flex; flex-direction: column; gap: 2px; }
.point-text b {
  font-size: 14px; font-weight: 600; color: #fff;
  letter-spacing: .3px;
}
.point-text span { font-size: 12px; line-height: 1.7; color: rgba(203, 219, 255, .72); }

.brand-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  max-width: 460px;
}
.stat {
  border-radius: 12px;
  padding: 12px 10px;
  background: rgba(255, 255, 255, .06);
  border: 1px solid rgba(147, 197, 253, .16);
  text-align: center;
  backdrop-filter: blur(2px);
}
.stat b {
  display: block;
  font-size: 22px; font-weight: 700; line-height: 1.1;
  color: #dbeafe;
  font-variant-numeric: tabular-nums;
}
.stat span { display: block; margin-top: 4px; font-size: 11px; color: rgba(191, 210, 255, .6); }

.tech-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.tech-tags span {
  font-size: 11px;
  color: rgba(219, 234, 254, .8);
  background: rgba(255, 255, 255, .07);
  border: 1px solid rgba(147, 197, 253, .16);
  border-radius: 999px;
  padding: 3px 10px;
  letter-spacing: .3px;
}
.foot-line { font-size: 11px; color: rgba(191, 210, 255, .42); letter-spacing: .3px; }

.brand-main { z-index: 1; max-width: 520px; }
.brand-headline {
  font-size: 34px; line-height: 1.35; margin: 0 0 16px;
  font-weight: 700; letter-spacing: .5px;
}
.brand-desc { font-size: 15px; color: rgba(226, 236, 255, .78); margin: 0 0 28px; line-height: 1.7; }
.brand-points { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 14px; }
.brand-points li {
  display: flex; align-items: center; gap: 10px;
  font-size: 14px; color: rgba(226, 236, 255, .88);
}
.brand-points li .el-icon {
  width: 26px; height: 26px; border-radius: 8px;
  background: rgba(96, 165, 250, .18);
  color: #93c5fd; font-size: 15px;
}
.brand-foot { z-index: 1; font-size: 12px; color: rgba(203, 219, 255, .5); }

/* ---------- 右：表单 ---------- */
.form-panel {
  flex: 1 1 42%;
  display: flex; align-items: center; justify-content: center;
  padding: 40px 24px;
}
.login-card {
  width: 100%; max-width: 380px;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: 16px;
  box-shadow: var(--ad-shadow);
  padding: 32px 30px 26px;
}
.login-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--ad-text); }
.login-sub { margin: 6px 0 24px; font-size: 13px; color: var(--ad-text-secondary); }
.login-btn { width: 100%; height: 42px; font-size: 15px; letter-spacing: 4px; }

/* ---------- 身份选择 ---------- */
.role-switch {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 18px;
}
.role-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 10px 6px;
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius-sm);
  background: #fbfcfe;
  color: var(--ad-text-secondary);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: all .15s ease;
}
.role-item:hover { border-color: var(--el-color-primary-light-5); color: var(--ad-primary); }
.role-item.active {
  border-color: var(--ad-primary);
  background: var(--el-color-primary-light-9);
  color: var(--ad-primary);
  font-weight: 600;
  box-shadow: 0 2px 10px rgba(37, 99, 235, .12);
}

.demo-title {
  margin: 26px 0 10px;
  font-size: 12px; color: var(--ad-text-muted);
  display: flex; align-items: center; gap: 8px;
}
.demo-title::after { content: ''; flex: 1; height: 1px; background: var(--ad-border); }

.demo-accounts { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.demo-chip {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius-sm);
  background: #fbfcfe;
  cursor: pointer;
  transition: all .15s ease;
  font-family: inherit;
  text-align: left;
}
.demo-chip:hover { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.demo-chip.active { border-color: var(--ad-primary); background: var(--el-color-primary-light-9); }
.demo-role { font-size: 12px; color: var(--ad-text); font-weight: 500; }
.demo-user { font-size: 11px; color: var(--ad-text-muted); font-family: Consolas, monospace; }

.register-line {
  margin-top: 18px;
  text-align: center;
  font-size: 12px;
  color: var(--ad-text-muted);
}
.register-line a { color: var(--ad-primary); cursor: pointer; }
.register-line a:hover { text-decoration: underline; }

@media (max-width: 900px) {
  .brand-panel { display: none; }
  .form-panel { flex: 1 1 100%; }
}
</style>
