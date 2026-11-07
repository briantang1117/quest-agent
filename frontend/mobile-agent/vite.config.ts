import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { VantResolver } from 'unplugin-vue-components/resolvers'
import { overwriteClientIp } from '../proxy-client-ip'
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    base: '/m/',
    plugins: [vue(), Components({ dts: false, resolvers: [VantResolver()] })],
    server: {
      port: 5174,
      strictPort: true,
      proxy: env.DEV_API_PROXY_TARGET
        ? {
            '/gateway': {
              target: env.DEV_API_PROXY_TARGET,
              changeOrigin: true,
              configure: (proxy) => {
                proxy.on('proxyReq', overwriteClientIp)
              },
              rewrite: (path) => path.replace(/^\/gateway/, ''),
            },
          }
        : undefined,
    },
    preview: { port: 4174, strictPort: true },
  }
})
