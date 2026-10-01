import { defineConfig } from '@playwright/test'

// E2E_BASE_URL을 주면 이미 떠 있는 스택(예: docker-compose.prod.yml의 nginx)을 대상으로 실행하고 서버를 띄우지 않는다.
// 없으면 backend(dev 프로파일)와 Vite dev server를 함께 띄운다. 포트는 병행 실행을 위해 바꿀 수 있다.
const external = process.env.E2E_BASE_URL
const backendPort = Number(process.env.E2E_BACKEND_PORT ?? 8080)
const frontendPort = Number(process.env.E2E_FRONTEND_PORT ?? 5173)
const backendUrl = `http://localhost:${backendPort}`
const frontendUrl = `http://localhost:${frontendPort}`

export default defineConfig({
  testDir: './e2e',
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  webServer: external
    ? undefined
    : [
        {
          command: `../backend/gradlew -p ../backend bootRun --args="--spring.profiles.active=dev --server.port=${backendPort}"`,
          url: `${backendUrl}/actuator/health`,
          env: { ALLOWED_ORIGINS: frontendUrl, PUBLIC_BASE_URL: backendUrl },
          timeout: 180_000,
          reuseExistingServer: !process.env.CI,
        },
        {
          command: `npm run dev -- --host 127.0.0.1 --port ${frontendPort}`,
          url: frontendUrl,
          env: { API_PROXY_TARGET: backendUrl },
          reuseExistingServer: !process.env.CI,
        },
      ],
  use: {
    baseURL: external ?? frontendUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
