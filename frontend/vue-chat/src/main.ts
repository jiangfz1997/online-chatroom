import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router' // 新增：引入 router

const app = createApp(App)

app.use(router)               // 新增：启用 vue-router
app.mount('#app')