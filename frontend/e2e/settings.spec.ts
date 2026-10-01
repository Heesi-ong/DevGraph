import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

// Phase 7 critical suite(설계서 §19): 비밀번호 변경·세션·재인증·Export·영구 삭제·계정 삭제와 취소·접근성.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

const PASSWORD = 'correct-horse-battery'

async function uiSignup(page: Page) {
  const email = `e2e-set-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  await page.goto('/signup')
  await page.getByLabel('이메일').fill(email)
  await page.getByLabel('표시 이름').fill('Settings E2E')
  await page.getByLabel('비밀번호(8자 이상)').fill(PASSWORD)
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  return email
}

async function uiLogin(page: Page, email: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('이메일').fill(email)
  await page.getByLabel('비밀번호').fill(password)
  await page.getByRole('button', { name: '로그인' }).click()
}

async function reauth(page: Page, password: string, confirm: string) {
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('현재 비밀번호').fill(password)
  await dialog.getByRole('button', { name: confirm }).click()
}

test('비밀번호를 바꾸면 새 비밀번호로만 로그인되고 세션 목록에 현재 기기가 보인다', async ({ page }) => {
  const email = await uiSignup(page)
  await page.getByRole('link', { name: '설정' }).click()
  await expect(page.getByRole('heading', { name: '설정' })).toBeVisible()
  await expect(page.getByRole('list', { name: '세션 목록' }).getByText('현재 기기')).toBeVisible()

  const section = page.getByRole('region', { name: '비밀번호 변경' })
  await section.getByLabel('현재 비밀번호').fill('wrong-password-xx')
  await section.getByLabel('새 비밀번호(8자 이상)').fill('another-secret-123')
  await section.getByRole('button', { name: '비밀번호 변경' }).click()
  await expect(section.getByRole('alert')).toContainText('현재 비밀번호가 올바르지 않습니다')

  await section.getByLabel('현재 비밀번호').fill(PASSWORD)
  await section.getByRole('button', { name: '비밀번호 변경' }).click()
  await expect(section.getByRole('status')).toContainText('변경했습니다')

  await page.getByRole('button', { name: '로그아웃' }).click()
  await uiLogin(page, email, PASSWORD)
  await expect(page.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeVisible()
  await uiLogin(page, email, 'another-secret-123')
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
})

test('Export: 재인증 → 진행 → 일회성 ZIP 다운로드', async ({ page }) => {
  await uiSignup(page)
  await page.goto('/')
  await page.getByLabel('제목').fill('내보낼 지식')
  await page.getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('heading', { name: '내보낼 지식' })).toBeVisible()

  await page.goto('/settings')
  await page.getByRole('button', { name: '내보내기 만들기' }).click()
  await reauth(page, 'wrong-password-xx', '내보내기 시작')
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('비밀번호가 올바르지 않습니다')
  await reauth(page, PASSWORD, '내보내기 시작')

  const done = page.getByRole('button', { name: 'ZIP 다운로드' })
  await expect(done).toBeVisible({ timeout: 30_000 })
  const [download] = await Promise.all([page.waitForEvent('download'), done.click()])
  expect(download.suggestedFilename()).toMatch(/^devgraph-export-.*\.zip$/)
  // 한 번 받은 링크는 다시 쓸 수 없다: 새로 조회하면 만료 상태가 된다.
  await page.reload()
  await expect(page.getByText('이전 내보내기는 만료되었습니다')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByRole('button', { name: '내보내기 만들기' })).toBeEnabled()
})

test('휴지통 항목은 재인증 뒤에만 영구 삭제된다', async ({ page }) => {
  await uiSignup(page)
  await page.goto('/')
  await page.getByLabel('제목').fill('지울 지식')
  await page.getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('heading', { name: '지울 지식' })).toBeVisible()
  await expect(page.getByRole('button', { name: '영구 삭제' })).toHaveCount(0) // ACTIVE에는 없다

  page.once('dialog', (d) => void d.accept())
  await page.getByRole('button', { name: '휴지통으로' }).click()
  await expect(page.getByRole('button', { name: '보관함으로 복구' })).toBeVisible()
  await page.getByRole('button', { name: '영구 삭제' }).click()
  await reauth(page, PASSWORD, '영구 삭제')
  await expect(page).toHaveURL(/\/library$/)
  await page.getByLabel('검색').first().fill('지울 지식').catch(() => undefined)
  await expect(page.getByText('지울 지식')).toHaveCount(0)
})

test('계정 삭제를 요청하면 로그아웃되고, 다시 로그인하면 취소만 할 수 있다', async ({ page }) => {
  const email = await uiSignup(page)
  await page.goto('/settings')
  const danger = page.getByRole('region', { name: '계정 삭제' })
  await expect(danger.getByRole('button', { name: '계정 삭제 요청' })).toBeDisabled()
  await danger.getByLabel(/확인을 위해 이메일/).fill(email)
  await danger.getByRole('button', { name: '계정 삭제 요청' }).click()
  await reauth(page, PASSWORD, '삭제 요청')
  await expect(page).toHaveURL(/\/login$/)

  await uiLogin(page, email, PASSWORD)
  await expect(page.getByRole('heading', { name: '계정 삭제가 예약되어 있습니다' })).toBeVisible()
  await expect(page.getByRole('link', { name: '설정' })).toHaveCount(0) // 제한 세션은 일반 화면에 못 간다
  await page.getByRole('button', { name: '삭제 취소' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
})

test('주요 화면에 접근성 위반(critical/serious)이 없고 360px에서 가로 스크롤이 없다', async ({ page }) => {
  await uiSignup(page)
  await page.setViewportSize({ width: 360, height: 800 })
  for (const path of ['/', '/library', '/settings']) {
    await page.goto(path)
    await expect(page.getByRole('navigation', { name: '주 메뉴' })).toBeVisible()
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    const bad = results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
    expect(bad.map((v) => `${v.id}: ${v.nodes[0]?.target}`), path).toEqual([])
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, `${path} horizontal overflow`).toBeLessThanOrEqual(0)
  }
})
