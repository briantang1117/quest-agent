import { defineConfig } from 'vitest/config'
export default defineConfig({
  test: {
    // Node 25+ exposes its own localStorage; let jsdom own browser storage in workers.
    pool: 'forks',
    poolOptions: { forks: { execArgv: ['--no-experimental-webstorage'] } },
  },
})
