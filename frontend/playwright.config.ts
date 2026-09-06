import { defineConfig } from '@playwright/test'

// E2E 主链路：登录 → 业务页 → Agent 写闸门确认 UX（浏览器级回归）。
// 前置（脚本只管前端，栈由外部保证）：postgres/redis 容器 + 后端 8080（AI_MOCK=true 即可）。
// 本地：docker compose up -d postgres redis && mvn spring-boot:run，然后 npm run e2e。
// 写链路落库断言在 API 层（MockMvc H2 + Testcontainers 真 PG）与跨进程演练中覆盖，
// E2E 只验证浏览器到后端的完整接线与确认交互——职责单一，避免 mock 模型的不确定性。
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:3000',
    trace: 'retain-on-failure',
  },
  webServer: process.env.E2E_BASE_URL
    ? undefined // CI 已另行起好前端
    : {
        command: 'npm run dev',
        url: 'http://localhost:3000',
        reuseExistingServer: true,
        timeout: 120_000,
      },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
