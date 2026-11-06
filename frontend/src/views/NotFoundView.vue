<template>
  <div class="nf-wrap">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <h2 class="nf-title">这个页面不存在</h2>
      <p class="nf-desc">
        地址可能输错了，或者这条记录已经被删除。<br />
        你可以回到订单列表继续操作。
      </p>
      <div class="nf-actions">
        <el-button type="primary" @click="$router.push('/orders')">回到订单列表</el-button>
        <el-button @click="$router.back()">返回上一页</el-button>
      </div>
      <p class="nf-hint ad-muted">如果是点击应用内的链接进来的，请把地址发给管理员排查。</p>
    </div>
  </div>
</template>

<script setup>
// 兜底页：路由匹配不到时显示，避免直接跳回首页让人困惑
</script>

<style scoped>
.nf-wrap {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}
.nf-card {
  text-align: center;
  max-width: 460px;
  background: var(--ad-surface);
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  box-shadow: var(--ad-shadow);
  padding: 40px 32px 28px;
}
.nf-code {
  font-size: 64px;
  font-weight: 800;
  letter-spacing: 4px;
  line-height: 1;
  background: linear-gradient(135deg, #2563eb, #60a5fa);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.nf-title { margin: 14px 0 8px; font-size: 18px; color: var(--ad-text); }
.nf-desc { margin: 0 0 22px; font-size: 13px; line-height: 1.8; color: var(--ad-text-secondary); }
.nf-actions { display: flex; gap: 10px; justify-content: center; }
.nf-hint { margin-top: 22px; font-size: 12px; }
</style>
