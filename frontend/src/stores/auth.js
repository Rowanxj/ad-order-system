import { defineStore } from 'pinia'
import { api } from '@/api'

/** 登录态管理：token 与用户信息持久化到 localStorage */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('ad_token') || '',
    user: JSON.parse(localStorage.getItem('ad_user') || 'null')
  }),
  getters: {
    isLoggedIn: s => !!s.token,
    role: s => s.user?.role || ''
  },
  actions: {
    /**
     * 登录。
     * role 是"我以什么身份登录"，后端会校验账号角色是否与之一致，
     * 不一致直接拒绝——避免拿着设计人员账号却以为在做经理的操作。
     */
    async login(username, password, role) {
      const data = await api.login({ username, password, role })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('ad_token', data.token)
      localStorage.setItem('ad_user', JSON.stringify(data.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('ad_token')
      localStorage.removeItem('ad_user')
    }
  }
})
