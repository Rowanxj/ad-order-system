/** 状态、类型与角色的统一中文映射 */

export const ORDER_STATUS = {
  PENDING_CONFIRM: { label: '待确认', type: 'warning' },
  IN_PROGRESS: { label: '进行中', type: 'primary' },
  COMPLETED: { label: '已交付', type: 'success' },
  CANCELLED: { label: '已取消', type: 'info' }
}

export const TASK_STATUS = {
  PENDING: { label: '待分派', type: 'info' },
  DESIGNING: { label: '设计中', type: 'primary' },
  PENDING_CONFIRM: { label: '待客户确认', type: 'warning' },
  REVISION: { label: '修改中', type: 'danger' },
  CONFIRMED: { label: '已确认', type: 'success' }
}

/**
 * 任务状态的中文展示。
 *
 * 业务上「分派」不是一次状态迁移：它只写 assignee_id，status 仍是 PENDING，
 * 这样同一个任务可以被反复改派而不改变状态机。代价是 PENDING 这一个状态
 * 对应两种不同处境，标签必须分开说：
 *   PENDING + 没有分派人 = 待分派
 *   PENDING + 已有分派人 = 待开始（设计人员还没点“开始设计”）
 * 状态码一个都没变，只是翻译时多看一个字段。
 * assigneeId 传任何“真值”都可以：订单详情传真实分派人 id；“我的任务”里
 * 每一行本来就都是分派给自己的，直接传 true。
 */
export function taskStatusText(status, assigneeId) {
  if (status === 'PENDING' && assigneeId) {
    return '待开始'
  }
  return TASK_STATUS[status]?.label || status
}

/**
 * 截止日期的提醒分档（订单与任务共用）。
 *
 *  - 已结束（已交付 / 已取消）不提醒：事情已经完了，再标红只会干扰。
 *  - 已逾期：红色，并给出超期天数。
 *  - 今天到期 / 3 天内：琥珀色，给出剩余天数。
 *  - 其他：不标色。
 *
 * 返回值 { cls, text, days }：cls 用于加样式，text 作为悬浮提示。
 */
export function deadlineMeta(deadline, status) {
  if (!deadline || status === 'COMPLETED' || status === 'CANCELLED') {
    return { cls: '', text: '', days: null }
  }
  const startOfDay = d => {
    const x = new Date(d)
    x.setHours(0, 0, 0, 0)
    return x
  }
  const days = Math.round((startOfDay(deadline) - startOfDay(new Date())) / 86400000)

  if (days < 0) return { cls: 'deadline-overdue', text: `已逾期 ${-days} 天`, days }
  if (days === 0) return { cls: 'deadline-warn', text: '今天到期', days }
  if (days <= 3) return { cls: 'deadline-warn', text: `还剩 ${days} 天`, days }
  return { cls: '', text: `还剩 ${days} 天`, days }
}

export const MATERIAL_TYPE = {
  POSTER: '海报',
  STAND: '展架',
  MENU: '菜单',
  BROCHURE: '宣传册',
  ONLINE: '线上配图'
}

export const ROLE = {
  SALES: '客户经理',
  MANAGER: '业务负责人',
  DESIGNER: '设计制作人员'
}

export function fmtDate(v) {
  if (!v) return '-'
  return String(v).slice(0, 10)
}

export function fmtTime(v) {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

export function fmtSize(bytes) {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}
