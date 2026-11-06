import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

/**
 * Axios 封装：
 * - 请求自动携带 Bearer Token
 * - 响应统一解包 {code, message, data}；code != 0 时弹错并 reject
 * - 401 清理本地登录态并跳回登录页
 *
 * 可选项 silent：请求配置里带 { silent: true } 时，**不弹全局错误提示**。
 * 用于后台轮询类请求（例如待办提醒）——它们失败不该打断用户正在做的事，
 * 也不该每切一次页面就弹一次「请求的资源不存在」。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use(cfg => {
  const token = localStorage.getItem('ad_token')
  if (token) cfg.headers.Authorization = 'Bearer ' + token
  return cfg
})

request.interceptors.response.use(
  resp => {
    // 文件下载等二进制响应直接返回
    if (resp.config.responseType === 'blob') return resp
    const body = resp.data
    if (body.code !== 0) {
      if (body.code === 401) {
        localStorage.removeItem('ad_token')
        localStorage.removeItem('ad_user')
        router.push('/login')
      }
      if (!resp.config.silent) {
        ElMessage.error(body.message || '请求失败')
      }
      return Promise.reject(body)
    }
    return body.data
  },
  err => {
    const msg = err.response?.data?.message || err.message || '网络错误'
    if (err.response?.status === 401) {
      localStorage.removeItem('ad_token')
      localStorage.removeItem('ad_user')
      router.push('/login')
    }
    if (!err.config?.silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

/** 设计稿下载（带鉴权，返回 blob） */
export function downloadFile(filePath) {
  return request.get('/files/' + filePath, { responseType: 'blob' })
}

export default request
