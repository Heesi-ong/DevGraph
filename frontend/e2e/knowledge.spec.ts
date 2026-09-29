import { expect, test, type Page } from '@playwright/test'

// Phase 2 완료 조건(설계서 §19): Concept/Note 생명주기와 URL 기반 필터가 E2E로 통과한다.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

async function signup(page: Page) {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  await page.goto('/signup')
  await page.getByLabel('이메일').fill(email)
  await page.getByLabel('표시 이름').fill('E2E Tester')
  await page.getByLabel('비밀번호(8자 이상)').fill('correct-horse-battery')
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
}

test('Concept lifecycle: create, tag, favorite, filter by URL, archive, trash', async ({ page }) => {
  await signup(page)

  // Dashboard 빈 상태 → Quick Create
  await expect(page.getByText('아직 지식이 없습니다')).toBeVisible()
  await page.getByLabel('제목').fill('Spring Security')
  await page.getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('heading', { name: 'Spring Security' })).toBeVisible()

  // 편집: 요약·본문·새 태그
  await page.getByRole('link', { name: '편집' }).click()
  await page.getByLabel('요약').fill('인증과 인가 프레임워크')
  await page.locator('#body-input').fill('# 개요\n\n필터 체인 기반')
  await page.getByPlaceholder('태그 검색 또는 새로 만들기').fill('spring')
  await page.getByRole('button', { name: /새 태그 만들기/ }).click()
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: '개요' })).toBeVisible()
  await expect(page.getByLabel('태그').getByText('spring')).toBeVisible()

  // 즐겨찾기
  await page.getByRole('button', { name: '즐겨찾기 추가' }).click()
  await expect(page.getByRole('button', { name: '즐겨찾기 해제' })).toBeVisible()

  // Note도 하나 만들어 URL 필터를 검증한다.
  await page.goto('/nodes/new')
  await page.getByLabel('유형').selectOption('NOTE')
  await page.getByLabel('제목', { exact: true }).fill('회의 메모')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: '회의 메모' })).toBeVisible()

  await page.goto('/library')
  await expect(page.getByText('Spring Security')).toBeVisible()
  await expect(page.getByText('회의 메모')).toBeVisible()

  await page.goto('/library?type=NOTE')
  await expect(page.getByText('회의 메모')).toBeVisible()
  await expect(page.getByText('Spring Security')).toHaveCount(0)

  await page.goto('/library?favorite=true')
  await expect(page.getByText('Spring Security')).toBeVisible()
  await expect(page.getByText('회의 메모')).toHaveCount(0)

  // 새로고침해도 필터가 유지된다.
  await page.reload()
  await expect(page.getByText('Spring Security')).toBeVisible()
  await expect(page.getByText('회의 메모')).toHaveCount(0)

  // 보관 → 기본 목록에서 사라지고 status=ARCHIVED에서 보인다.
  await page.getByRole('link', { name: 'Spring Security' }).click()
  await page.getByRole('button', { name: '보관', exact: true }).click()
  await expect(page.getByText('상태: 보관됨')).toBeVisible()
  await page.goto('/library')
  await expect(page.getByText('Spring Security')).toHaveCount(0)
  await page.goto('/library?status=ARCHIVED')
  await expect(page.getByText('Spring Security')).toBeVisible()

  // 휴지통 → 편집 링크가 사라지고, 복구는 보관함으로 돌아간다.
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('link', { name: 'Spring Security' }).click()
  await page.getByRole('button', { name: '휴지통으로' }).click()
  await expect(page.getByText('상태: 휴지통')).toBeVisible()
  await expect(page.getByRole('link', { name: '편집' })).toHaveCount(0)
  await page.getByRole('button', { name: '보관함으로 복구' }).click()
  await expect(page.getByText('상태: 보관됨')).toBeVisible()
})

test('Edit conflict: stale edit shows both versions and lets the user choose', async ({ page, browser }) => {
  await signup(page)
  await page.getByLabel('제목').fill('충돌 테스트')
  await page.getByRole('button', { name: '만들기' }).click()
  await expect(page).toHaveURL(/\/nodes\/[0-9a-f-]{36}$/)
  const url = page.url()

  // 탭 A가 편집 화면을 연 사이 탭 B가 먼저 저장한다.
  await page.goto(`${url}/edit`)
  await expect(page.getByLabel('제목', { exact: true })).toHaveValue('충돌 테스트')

  const other = await browser.newPage()
  await other.context().addCookies(await page.context().cookies())
  await other.goto(`${url}/edit`)
  await other.getByLabel('제목', { exact: true }).fill('B가 바꾼 제목')
  await other.getByRole('button', { name: '저장' }).click()
  await expect(other.getByRole('heading', { name: 'B가 바꾼 제목' })).toBeVisible()
  await other.close()

  await page.getByLabel('제목', { exact: true }).fill('A가 바꾼 제목')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: '다른 곳에서 먼저 수정되었습니다' })).toBeVisible()
  await expect(page.getByLabel('제목', { exact: true })).toHaveValue('A가 바꾼 제목')

  await page.getByRole('button', { name: '내 입력으로 덮어쓰기' }).click()
  await expect(page.getByRole('heading', { name: 'A가 바꾼 제목' })).toBeVisible()
})

test('unauthenticated access to a knowledge page redirects to login', async ({ page }) => {
  await page.goto('/library')
  await expect(page).toHaveURL(/\/login/)
})
