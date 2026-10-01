import { defineConfig } from '@playwright/test'

// E2E_BASE_URL을 주면 이미 떠 있는 스택(예: docker-compose.prod.yml의 nginx)을 대상으로 실행한다.
const external = process.env.E2E_BASE_URL

export default defineConfig({
  testDir: './e2e',
  webServer: external
    ? undefined
    : {
        command: 'npm run dev',
        port: 5173,
        reuseExistingServer: !process.env.CI,
      },
  use: {
    baseURL: external ?? 'http://localhost:5173',
  },
})
