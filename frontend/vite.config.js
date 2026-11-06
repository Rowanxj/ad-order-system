import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 前端开发服务器代理到后端，避免跨域
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 把体积大、更新少的依赖拆成独立 chunk，业务代码改动时仍能命中缓存
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-http': ['axios']
        }
      }
    },
    // Element Plus 全量引入后单 chunk 约 1MB，这是已知取舍
    chunkSizeWarningLimit: 1100
  }
})
