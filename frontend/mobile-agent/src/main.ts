import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { useConnectionStore } from '@quest/shared/connection'
import { router } from './router'
import App from './App.vue'
import '@quest/shared/fonts.css'
import './style.css'
import { initializeErrorFeedback } from './error-feedback'
initializeErrorFeedback()
const app = createApp(App)
app.use(createPinia())
useConnectionStore().configure(import.meta.env.VITE_API_BASE_URL)
app.use(router).mount('#app')
