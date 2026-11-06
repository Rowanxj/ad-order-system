<template>
  <div class="nb-wrap">
    <!-- 当前时间 -->
    <div class="nb-clock">
      <el-icon><Clock /></el-icon>
      <span class="nb-date">{{ today }}</span>
      <span class="nb-time">{{ clock }}</span>
    </div>

    <!-- 待办提醒：后端未部署该接口时自动隐藏，不留一个点了报错的铃铛 -->
    <el-popover v-if="supported" placement="bottom-end" :width="380" trigger="click" popper-class="nb-popper">
      <template #reference>
        <div class="nb-bell" :title="total ? `你有 ${total} 项待办` : '暂无待办'">
          <el-badge :value="total" :hidden="!total" :max="99">
            <el-icon :size="19"><Bell /></el-icon>
          </el-badge>
        </div>
      </template>

      <div class="nb-head">
        <span class="nb-title">我的待办</span>
        <span class="ad-muted">{{ total ? `${total} 项待处理` : '全部处理完了' }}</span>
      </div>

      <div v-if="items.length" class="nb-items">
        <div v-for="it in items" :key="it.label" class="nb-item" @click="go(it.path)">
          <span class="nb-item-label">{{ it.label }}</span>
          <span class="nb-item-hint">{{ it.hint }}</span>
          <span class="nb-count">{{ it.count }}</span>
        </div>
      </div>
      <div v-else class="nb-empty">
        <el-icon :size="26"><CircleCheck /></el-icon>
        <p>没有需要你处理的单据</p>
      </div>

      <template v-if="recent.length">
        <div class="nb-sub">最近消息</div>
        <div class="nb-recent">
          <div v-for="(r, i) in recent" :key="i" class="nb-recent-item" @click="go(r.path)">
            <span class="nb-recent-text">{{ r.text }}</span>
            <span class="nb-recent-meta">
              <el-tag size="small" effect="plain" type="warning">{{ r.label }}</el-tag>
            </span>
          </div>
        </div>
      </template>

      <div class="nb-foot">
        <span class="ad-muted">每 60 秒自动刷新</span>
        <el-button link type="primary" size="small" @click="load">立即刷新</el-button>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api'

const router = useRouter()
const route = useRoute()

const clock = ref('')
const today = ref('')
const total = ref(0)
const items = ref([])
const recent = ref([])
/** 后端是否已提供该接口；老 jar 没有这个路由时自动隐藏铃铛，避免反复弹错误提示 */
const supported = ref(true)

let timer = null
let poller = null

function tick() {
  const d = new Date()
  const pad = n => String(n).padStart(2, '0')
  clock.value = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  today.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} 周${week}`
}

async function load() {
  // 已经确认后端没有这个接口（老 jar），就彻底停手：
  // 否则每次切换页面都会再发一次请求，虽然不再弹提示，但也没意义。
  if (!supported.value) return
  try {
    const data = await api.notificationSummary()
    total.value = data?.total || 0
    items.value = data?.items || []
    recent.value = data?.recent || []
  } catch {
    supported.value = false
    if (poller) clearInterval(poller)
  }
}

function go(path) {
  if (path) router.push(path)
}

// 页面切换后（比如点了确认、分派）待办数量会变，顺手刷新一次
watch(() => route.fullPath, () => load())

onMounted(() => {
  tick()
  timer = setInterval(tick, 1000)
  load()
  poller = setInterval(load, 60000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (poller) clearInterval(poller)
})
</script>

<style scoped>
.nb-wrap { display: flex; align-items: center; gap: 16px; }

.nb-clock {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; color: var(--ad-text-secondary);
  background: #f6f8fc;
  border: 1px solid var(--ad-border);
  border-radius: 999px;
  padding: 4px 12px;
}
.nb-date { white-space: nowrap; }
.nb-time {
  font-family: Consolas, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--ad-text);
  font-weight: 600;
}

.nb-bell {
  width: 34px; height: 34px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 10px;
  color: var(--ad-text-secondary);
  cursor: pointer;
  transition: background .15s ease, color .15s ease;
}
.nb-bell:hover { background: #f2f5fb; color: var(--ad-primary); }

.nb-head {
  display: flex; align-items: baseline; justify-content: space-between;
  padding-bottom: 10px; margin-bottom: 6px;
  border-bottom: 1px solid var(--ad-border);
}
.nb-title { font-size: 14px; font-weight: 600; color: var(--ad-text); }

.nb-items { display: flex; flex-direction: column; gap: 2px; }
.nb-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
}
.nb-item:hover { background: var(--el-color-primary-light-9); }
.nb-item-label { font-size: 13px; font-weight: 500; color: var(--ad-text); white-space: nowrap; }
.nb-item-hint {
  font-size: 12px; color: var(--ad-text-muted);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.nb-count {
  min-width: 22px; text-align: center;
  font-size: 12px; font-weight: 700;
  color: #fff; background: var(--ad-primary);
  border-radius: 999px; padding: 1px 7px;
  font-family: Consolas, monospace;
}

.nb-empty {
  text-align: center; padding: 22px 0 14px;
  color: #16a34a;
}
.nb-empty p { margin: 8px 0 0; font-size: 13px; color: var(--ad-text-secondary); }

.nb-sub {
  margin: 12px 0 6px;
  font-size: 12px; color: var(--ad-text-muted);
  display: flex; align-items: center; gap: 8px;
}
.nb-sub::after { content: ''; flex: 1; height: 1px; background: var(--ad-border); }

.nb-recent { display: flex; flex-direction: column; }
.nb-recent-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 8px;
  border-radius: 8px;
  cursor: pointer;
}
.nb-recent-item:hover { background: #f8fafc; }
.nb-recent-text {
  font-size: 12px; color: var(--ad-text-secondary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.nb-foot {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 10px; padding-top: 8px;
  border-top: 1px solid var(--ad-border);
}
</style>
