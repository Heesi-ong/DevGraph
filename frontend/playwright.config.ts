import { defineConfig } from '@playwright/test'

const backendPort = Number(process.env.E2E_BACKEND_PORT ?? 8080)
const frontendPort = Number(process.env.E2E_FRONTEND_PORT ?? 5173)
const backendUrl = `http://localhost:${backendPort}`
const frontendUrl = `http://localhost:${frontendPort}`

export default defineConfig({
  testDir: './e2e',
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  webServer: [{
    command: `../backend/gradlew -p ../backend bootRun --args="--spring.profiles.active=dev --server.port=${backendPort}"`,
    url: `${backendUrl}/actuator/health`,
    env: { ALLOWED_ORIGINS: frontendUrl, PUBLIC_BASE_URL: backendUrl },
    timeout: 180_000,
    reuseExistingServer: !process.env.CI,
  }, {
    command: `npm run dev -- --host 127.0.0.1 --port ${frontendPort}`,
    url: frontendUrl,
    env: { API_PROXY_TARGET: backendUrl },
    reuseExistingServer: !process.env.CI,
  }],
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
