import { expect, test } from '@playwright/test'

// Phase 0 완료 조건(설계서 19절): 빈 앱이 기동되고 backend health를 확인한다.
test('home page loads and shows the DevGraph shell', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'DevGraph' })).toBeVisible()
})
