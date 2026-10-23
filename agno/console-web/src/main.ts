import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import { bootstrapAuth } from './api'

async function start() {
  await bootstrapAuth()
  createApp(App).use(ElementPlus).mount('#app')
}

start()
