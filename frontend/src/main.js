import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import './styles/ops-console-theme.css'
import './styles/ops-page-layout.css'
import App from './App.vue'

// 创建Vue应用
const app = createApp(App)

// 使用Element Plus
app.use(ElementPlus)

// 注册所有Element Plus图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 全局样式和动画
app.config.globalProperties.$animate = {
  fadeIn: 'animate__fadeIn',
  fadeInUp: 'animate__fadeInUp',
  zoomIn: 'animate__zoomIn',
  slideInRight: 'animate__slideInRight'
}

// 挂载应用
app.mount('#app')
