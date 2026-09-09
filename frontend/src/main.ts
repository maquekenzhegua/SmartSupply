import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 全局设计体系：设计令牌 + Element Plus 主题覆盖 + 通用组件样式
import '@/styles/index.css'
import App from './App.vue'
import router from './router'
// 官方图标包全量注册，模板里可直接 <el-icon><Goods /></el-icon> 使用
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

const app = createApp(App)
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
