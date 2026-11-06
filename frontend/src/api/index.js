import request, { downloadFile } from './request'

/** 全部后端接口的封装（与 ApiController 一一对应） */
export const api = {
  // 认证
  login: data => request.post('/auth/login', data),
  me: () => request.get('/auth/me'),
  register: data => request.post('/auth/register', data),
  changeMyPassword: data => request.put('/auth/password', data),

  // 客户
  customers: keyword => request.get('/customers', { params: { keyword } }),
  createCustomer: data => request.post('/customers', data),

  // 订单
  orders: params => request.get('/orders', { params }),
  orderStats: () => request.get('/orders/stats', { silent: true }),
  createOrder: data => request.post('/orders', data),
  orderDetail: id => request.get(`/orders/${id}`),
  confirmOrder: id => request.put(`/orders/${id}/confirm`),
  cancelOrder: id => request.put(`/orders/${id}/cancel`),
  completeOrder: id => request.put(`/orders/${id}/complete`),
  updateOrderDeadline: (id, deadline) => request.put(`/orders/${id}/deadline`, { deadline }),
  addTask: (id, data) => request.post(`/orders/${id}/tasks`, data),

  // 任务
  tasks: params => request.get('/tasks', { params }),
  myTasks: status => request.get('/tasks/my', { params: { status } }),
  taskDetail: id => request.get(`/tasks/${id}`),
  assignTask: (id, assigneeId) => request.put(`/tasks/${id}/assign`, { assigneeId }),
  startTask: id => request.put(`/tasks/${id}/start`),
  uploadVersion: (id, formData) => request.post(`/tasks/${id}/versions`, formData),
  recordFeedback: (id, content) => request.post(`/tasks/${id}/feedbacks`, { content }),
  confirmTask: id => request.put(`/tasks/${id}/confirm`),
  updateTaskDeadline: (id, deadline) => request.put(`/tasks/${id}/deadline`, { deadline }),

  // 用户
  designers: () => request.get('/users/designers'),

  // 账号管理（仅负责人）
  users: () => request.get('/users'),
  createUser: data => request.post('/users', data),
  changeUserStatus: (id, status) => request.put(`/users/${id}/status`, { status }),
  resetUserPassword: (id, password) => request.put(`/users/${id}/password`, { password }),

  // 待办提醒（按角色返回汇总，任何登录用户可调）
  // silent: 后台轮询失败不弹全局提示，避免刷屏
  notificationSummary: () => request.get('/notifications/summary', { silent: true })
}

// 设计稿下载（带鉴权，返回 blob）。原来 TaskDetailView 从 '@/api' 里取不到它，
// 导致「下载设计稿」在打包时直接报 "downloadFile is not exported"，这里统一出口。
export { downloadFile }
