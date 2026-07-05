import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './styles.css'
import './styles/tech-theme.css'
import { theme } from './theme' // 副作用：按 localStorage 立即应用主题，避免首屏闪烁

const app = createApp(App)
app.use(router)
app.use(ElementPlus)
// 全局注册所有图标（避免模板里 <User/> 等未注册导致渲染失败）
for (const [k, c] of Object.entries(Icons)) app.component(k, c as any)
app.mount('#app')
