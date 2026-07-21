/// <reference types="node" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src') // ✅ 新增别名配置
    }
  },
  server: {
    host: '0.0.0.0', // 允许外部访问
    port: 5173, // 端口号
    watch: {
    usePolling: true,
    },
    proxy: {
      // '/api': {
      //   target: 'http://host.docker.internal:8080', // ✅ 開發時後端地址
      //   changeOrigin: true,
      //   rewrite: (path) => path.replace(/^\/api/, ''),
      // },
    }
  },
})
