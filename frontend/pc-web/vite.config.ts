import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ArcoResolver } from 'unplugin-vue-components/resolvers'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), Components({ dts: false, resolvers: [ArcoResolver({ sideEffect: true })] })],
    server: { port: 5173, strictPort: true, proxy: env.DEV_API_PROXY_TARGET ? { '/gateway': { target: env.DEV_API_PROXY_TARGET, changeOrigin: true, rewrite: path => path.replace(/^\/gateway/, '') } } : undefined },
    preview: { port: 4173, strictPort: true },
  }
})
