<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="aside" :class="{ collapsed }">
      <div class="brand" @click="$router.push('/dashboard')">
        <div class="brand-logo">物</div>
        <div v-show="!collapsed" class="brand-text">
          <div class="brand-title">宣传物料订单</div>
          <div class="brand-sub">广告制作 · 内部协作</div>
        </div>
      </div>

      <nav class="nav">
        <a v-for="item in menus" :key="item.path" class="nav-item"
           :class="{ active: isActive(item.path) }" @click="$router.push(item.path)">
          <el-icon :size="17"><component :is="item.icon" /></el-icon>
          <span v-show="!collapsed" class="nav-label">{{ item.label }}</span>
        </a>
      </nav>

      <div class="aside-footer">
        <div class="aside-user" v-show="!collapsed">
          <div class="avatar">{{ initial }}</div>
          <div class="aside-user-info">
            <div class="aside-user-name">{{ auth.user?.realName }}</div>
            <div class="aside-user-role">{{ ROLE[auth.role] || auth.role }}</div>
          </div>
        </div>
        <el-tooltip :content="collapsed ? '展开侧栏' : '收起侧栏'" placement="right">
          <a class="collapse-btn" @click="collapsed = !collapsed">
            <el-icon><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
          </a>
        </el-tooltip>
      </div>
    </aside>

    <!-- 主区域 -->
    <section class="body">
      <header class="header">
        <div class="header-left">
          <span class="header-title">{{ $route.meta.title || '工作台' }}</span>
          <span class="header-crumb">{{ crumb }}</span>
        </div>
        <div class="header-right">
          <NotificationBell />
          <el-tag size="small" effect="plain" round>{{ ROLE[auth.role] || auth.role }}</el-tag>
          <el-dropdown @command="onCommand">
            <span class="user-box">
              <span class="avatar avatar-sm">{{ initial }}</span>
              <span class="user-name">{{ auth.user?.realName }}</span>
              <span class="user-account">@{{ auth.user?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.username }}</el-dropdown-item>
                <el-dropdown-item divided command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="main">
        <router-view />
      </main>

      <!-- 修改本人密码 -->
      <el-dialog v-model="pwdDlg" title="修改密码" width="400px" append-to-body @closed="resetPwd">
        <el-form label-width="82px">
          <el-form-item label="原密码" required>
            <el-input v-model="pwdForm.oldPassword" show-password maxlength="32" />
          </el-form-item>
          <el-form-item label="新密码" required>
            <el-input v-model="pwdForm.newPassword" show-password placeholder="6–32 位" maxlength="32" />
          </el-form-item>
          <el-form-item label="确认新密码" required>
            <el-input v-model="pwdForm.confirm" show-password maxlength="32" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="pwdDlg = false">取消</el-button>
          <el-button type="primary" :loading="pwdSaving" @click="savePassword">保存</el-button>
        </template>
      </el-dialog>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ROLE } from '@/utils/constants'
import NotificationBell from '@/components/NotificationBell.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

// 窄屏自动收起，只保留图标导航
const collapsed = ref(typeof window !== 'undefined' && window.innerWidth < 1100)

const menus = computed(() => {
  const items = [{ path: '/dashboard', label: '工作台', icon: 'HomeFilled' }]
  items.push({ path: '/orders', label: '订单管理', icon: 'Document' })
  if (auth.role === 'SALES') items.push({ path: '/orders/new', label: '新建订单', icon: 'Plus' })
  if (auth.role === 'MANAGER') items.push({ path: '/tasks', label: '任务工作台', icon: 'List' })
  if (['SALES', 'MANAGER'].includes(auth.role)) items.push({ path: '/exceptions', label: '异常管理', icon: 'Warning' })
  if (auth.role === 'DESIGNER') items.push({ path: '/my-tasks', label: '我的设计任务', icon: 'Brush' })
  if (['SALES', 'MANAGER'].includes(auth.role)) items.push({ path: '/customers', label: '客户管理', icon: 'User' })
  if (auth.role === 'MANAGER') items.push({ path: '/users', label: '账号管理', icon: 'UserFilled' })
  return items
})

const initial = computed(() => (auth.user?.realName || '?').slice(0, 1))

const crumb = computed(() => {
  const p = route.path
  if (p === '/dashboard') return ' / 工作台'
  if (p === '/orders/new') return ' / 新建订单'
  if (/^\/orders\/\d+/.test(p)) return ' / 订单详情'
  if (p === '/exceptions') return ' / 异常管理'
  if (/^\/tasks\/\d+/.test(p)) return ' / 任务详情'
  if (p === '/tasks') return ' / 任务工作台'
  if (p === '/my-tasks') return ' / 我的任务'
  if (p === '/customers') return ' / 客户管理'
  if (p === '/users') return ' / 账号管理'
  return ' / 工作台'
})

function isActive(path) {
  const p = route.path
  if (path === '/dashboard') return p === '/dashboard'
  if (path === '/orders') {
    // 设计人员/客户经理没有“任务工作台”菜单，任务详情仍归在订单管理下
    return p === '/orders' || /^\/orders\/\d+$/.test(p)
      || (auth.role !== 'MANAGER' && /^\/tasks\/\d+$/.test(p))
  }
  if (path === '/exceptions') return p === '/exceptions'
  if (path === '/tasks') return p === '/tasks' || /^\/tasks\/\d+$/.test(p)
  if (path === '/orders/new') return p === '/orders/new'
  return p === path || p.startsWith(path + '/')
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  } else if (cmd === 'password') {
    pwdDlg.value = true
  }
}

// 修改本人密码
const pwdDlg = ref(false)
const pwdSaving = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

function resetPwd() {
  Object.assign(pwdForm, { oldPassword: '', newPassword: '', confirm: '' })
}

async function savePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) return ElMessage.warning('原密码和新密码都要填')
  if (pwdForm.newPassword.length < 6) return ElMessage.warning('新密码至少 6 位')
  if (pwdForm.newPassword !== pwdForm.confirm) return ElMessage.warning('两次输入的新密码不一致')
  pwdSaving.value = true
  try {
    await api.changeMyPassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码已修改，下次登录请使用新密码')
    pwdDlg.value = false
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.layout { height: 100vh; display: flex; overflow: hidden; }

/* ---------- 侧边栏 ---------- */
.aside {
  width: 232px;
  flex: 0 0 232px;
  background: var(--ad-sidebar);
  display: flex;
  flex-direction: column;
  transition: width .2s ease, flex-basis .2s ease;
}
.aside.collapsed { width: 68px; flex-basis: 68px; }

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  cursor: pointer;
  user-select: none;
}
.brand-logo {
  width: 36px; height: 36px; flex: 0 0 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff; font-size: 17px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 12px rgba(37, 99, 235, .35);
}
.brand-text { overflow: hidden; white-space: nowrap; }
.brand-title { color: #f8fafc; font-size: 14px; font-weight: 600; letter-spacing: .3px; }
.brand-sub { color: #7c8aa0; font-size: 11px; margin-top: 3px; }

.nav { flex: 1; padding: 6px 10px; display: flex; flex-direction: column; gap: 4px; overflow-y: auto; }
.nav-item {
  position: relative;
  display: flex; align-items: center; gap: 10px;
  height: 42px; padding: 0 12px;
  border-radius: var(--ad-radius-sm);
  color: #a8b4c6; font-size: 14px;
  cursor: pointer; user-select: none;
  transition: background .15s ease, color .15s ease;
  white-space: nowrap;
}
.nav-item:hover { background: rgba(255, 255, 255, .06); color: #e2e8f0; }
.nav-item.active {
  background: rgba(37, 99, 235, .18);
  color: #fff;
  font-weight: 500;
}
.nav-item.active::before {
  content: '';
  position: absolute; left: -10px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 20px; border-radius: 0 3px 3px 0;
  background: #60a5fa;
}
.aside.collapsed .nav-item { justify-content: center; padding: 0; }
.nav-label { overflow: hidden; text-overflow: ellipsis; }

.aside-footer {
  padding: 10px;
  border-top: 1px solid rgba(255, 255, 255, .07);
  display: flex; align-items: center; gap: 8px;
}
.aside-user { display: flex; align-items: center; gap: 9px; flex: 1; min-width: 0; }
.avatar {
  width: 32px; height: 32px; flex: 0 0 32px;
  border-radius: 50%;
  background: rgba(96, 165, 250, .22);
  color: #cfe0ff; font-size: 13px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.avatar-sm { width: 26px; height: 26px; flex-basis: 26px; font-size: 12px;
  background: var(--el-color-primary-light-9); color: var(--ad-primary); }
.aside-user-info { min-width: 0; }
.aside-user-name { color: #e2e8f0; font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aside-user-role { color: #7c8aa0; font-size: 11px; margin-top: 2px; }
.collapse-btn {
  width: 30px; height: 30px; border-radius: 8px;
  color: #94a3b8; display: flex; align-items: center; justify-content: center;
  cursor: pointer; flex: 0 0 30px;
}
.collapse-btn:hover { background: rgba(255, 255, 255, .08); color: #fff; }

/* ---------- 主区域 ---------- */
.body { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.header {
  height: 60px; flex: 0 0 60px;
  background: var(--ad-surface);
  border-bottom: 1px solid var(--ad-border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px;
}
.header-left { display: flex; align-items: baseline; gap: 8px; }
.header-title { font-size: 16px; font-weight: 600; color: var(--ad-text); }
.header-crumb { font-size: 13px; color: var(--ad-text-muted); }
.header-right { display: flex; align-items: center; gap: 14px; }
.user-box { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--ad-text); font-size: 14px; }
.user-name { font-weight: 500; }
.user-account { font-size: 12px; color: var(--ad-text-muted); font-family: Consolas, monospace; }

.main { flex: 1; overflow-y: auto; padding: 20px 24px 28px; background: var(--ad-bg); }

@media (max-width: 768px) {
  .main { padding: 14px; }
  .header { padding: 0 14px; }
  .header-crumb { display: none; }
}
</style>
