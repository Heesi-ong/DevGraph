import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    // e2e/는 Playwright 전용이다 — vitest가 같은 *.spec.ts 패턴으로 주워가지 않게 범위를 좁힌다.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
