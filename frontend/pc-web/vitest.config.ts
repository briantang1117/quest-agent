import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    pool: 'forks',
    poolOptions: { forks: { execArgv: ['--no-experimental-webstorage'] } },
  },
})
