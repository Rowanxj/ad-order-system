import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

/** 路由表：meta.roles 限定可访问角色，由全局守卫校验 */
const routes = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '工作台' } },
      { path: 'orders', component: () => import('@/views/OrderListView.vue'), meta: { title: '订单管理' } },
      { path: 'orders/new', component: () => import('@/views/OrderFormView.vue'), meta: { title: '新建订单', roles: ['SALES'] } },
      { path: 'orders/:id', component: () => import('@/views/OrderDetailView.vue'), meta: { title: '订单详情' } },
      { path: 'exceptions', component: () => import('@/views/ExceptionView.vue'), meta: { title: '异常管理', roles: ['SALES', 'MANAGER'] } },
      { path: 'tasks', component: () => import('@/views/TaskWorkbenchView.vue'), meta: { title: '任务工作台', roles: ['MANAGER'] } },
      { path: 'tasks/:id', component: () => import('@/views/TaskDetailView.vue'), meta: { title: '任务详情' } },
      { path: 'my-tasks', component: () => import('@/views/MyTasksView.vue'), meta: { title: '我的设计任务', roles: ['DESIGNER'] } },
      { path: 'customers', component: () => import('@/views/CustomerView.vue'), meta: { title: '客户管理', roles: ['SALES', 'MANAGER'] } },
      { path: 'users', component: () => import('@/views/UserManageView.vue'), meta: { title: '账号管理', roles: ['MANAGER'] } },
      // 兜底：匹配不到就显示 404 页（仍在布局内，侧边栏还在，不会让人失去方向）
      { path: ':pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面不存在' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(to => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && !to.meta.roles.includes(auth.role)) {
    ElMessage.warning('当前角色无权访问该页面')
    return '/dashboard'
  }
  document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '宣传物料订单管理系统'
  return true
})

export default router
