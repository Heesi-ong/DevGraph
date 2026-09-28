import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
// vitest 설정은 vitest.config.ts로 분리한다 — 'vitest/config'와 '@vitejs/plugin-react'가
// 서로 다른 번들 vite 타입을 참조해 한 파일에 합치면 타입 충돌이 난다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: process.env.PORT ? Number(process.env.PORT) : 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
})
