import { expect, test, type Page } from '@playwright/test'

// Phase 6: Problems 상태 전환과 경고, Resource URL 보안·중복 경고, Project 폼 검증, Library/Dashboard 연동.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

async function apiSignup(page: Page): Promise<string> {
  const email = `e2e-prob-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  const res = await page.request.post('/api/v1/auth/signup', {
    data: { email, displayName: 'Problems E2E', password: 'correct-horse-battery' },
  })
  expect(res.status()).toBe(201)
  return (await res.json()).accessToken
}

async function api(page: Page, token: string, path: string, data: unknown) {
  const res = await page.request.post(`/api/v1${path}`, { headers: { Authorization: `Bearer ${token}` }, data })
  expect(res.ok(), `${path} -> ${res.status()}`).toBe(true)
  return res.json()
}

test('marking an error resolved without a solution warns but saves; reopening and linking clears it', async ({ page }) => {
  const token = await apiSignup(page)
  const error = await api(page, token, '/errors', { title: 'Null pointer', errorMessage: 'java.lang.NullPointerException' })

  await page.goto(`/errors/${error.id}`)
  await expect(page.getByTestId('resolution-status')).toHaveText('열림')
  await page.getByLabel('바꿀 상태').selectOption({ label: '해결됨' })
  await page.getByRole('button', { name: '상태 변경' }).click()
  await expect(page.getByTestId('resolution-status')).toHaveText('해결됨')
  const warning = page.getByRole('status').filter({ hasText: '연결된 Solution이 없습니다' })
  await expect(warning).toBeVisible()
  await page.reload()
  await expect(warning).toBeVisible() // 저장된 상태에서도 경고가 유지된다.

  // 종료 상태에서는 '열림'으로만 되돌릴 수 있다(다른 선택지가 없다).
  await expect(page.getByLabel('바꿀 상태').locator('option')).toHaveText(['열림'])
  await page.getByRole('button', { name: '상태 변경' }).click()
  await expect(page.getByTestId('resolution-status')).toHaveText('열림')
  await expect(warning).toHaveCount(0)

  // 해결 방법을 연결한 뒤 해결됨으로 바꾸면 경고가 없다.
  await page.getByRole('link', { name: '해결 방법 추가' }).first().click()
  await page.getByLabel('제목').fill('널 검사')
  await page.getByLabel('접근 방법 (Markdown)').fill('Optional로 감싼다')
  await page.getByRole('button', { name: '저장' }).click()
  await page.goto(`/errors/${error.id}`)
  await page.getByLabel('바꿀 상태').selectOption({ label: '해결됨' })
  await page.getByRole('button', { name: '상태 변경' }).click()
  await expect(page.getByTestId('resolution-status')).toHaveText('해결됨')
  await expect(warning).toHaveCount(0)
})

test('problems list shows the chain preview, URL filters, and the dashboard lists unresolved errors', async ({ page }) => {
  const token = await apiSignup(page)
  const open = await api(page, token, '/errors', { title: 'Open one', errorMessage: 'open message' })
  await api(page, token, '/errors', { title: 'Second open', errorMessage: 'second' })
  const solved = await api(page, token, '/errors', { title: 'Solved one', errorMessage: 'solved message' })
  await api(page, token, '/solutions', { title: 'Fix', approachMd: 'x', errorNodeId: solved.id })
  const res = await page.request.patch(`/api/v1/errors/${solved.id}/status`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { version: 0, status: 'RESOLVED' }, // 관계 생성은 Error Node의 version을 올리지 않는다
  })
  expect(res.ok()).toBe(true)

  await page.goto('/')
  const unresolved = page.getByRole('region', { name: '해결 안 된 오류' })
  await expect(unresolved.getByRole('link', { name: 'Open one' })).toBeVisible()
  await expect(unresolved.getByRole('link', { name: 'Solved one' })).toHaveCount(0)
  await expect(unresolved.getByText('해결 방법 없음').first()).toBeVisible()

  await page.goto('/problems')
  const list = page.getByRole('list', { name: 'Error 목록' })
  await expect(list.getByRole('listitem')).toHaveCount(3)
  await expect(list.getByRole('listitem').filter({ hasText: 'Solved one' })).toContainText('해결 1')
  await expect(list.getByRole('listitem').filter({ hasText: 'Open one' })).toContainText('아직 해결 방법이 없습니다')

  // 해결 상태 필터는 URL에 남고 새로고침해도 유지된다.
  await page.getByRole('group', { name: '필터' }).getByLabel('해결됨').click()
  await expect(page).toHaveURL(/resolution=RESOLVED/)
  await expect(list.getByRole('listitem')).toHaveCount(1)
  await page.reload()
  await expect(list.getByRole('listitem')).toHaveCount(1)
  await expect(list.getByText('Solved one')).toBeVisible()

  // Solutions 탭
  await page.goto('/problems?tab=solutions')
  await expect(page.getByRole('list', { name: 'Solution 목록' }).getByText('해결하는 오류 1')).toBeVisible()

  // Library의 Error 필터는 Error 상세로 연결된다.
  await page.goto('/library?type=ERROR')
  await page.getByRole('link', { name: 'Open one' }).click()
  await expect(page).toHaveURL(new RegExp(`/errors/${open.id}`))
})

test('resources: dangerous URLs are rejected, duplicates only warn, and external links are safe', async ({ page }) => {
  await apiSignup(page)
  await page.goto('/resources/new')
  await page.getByLabel('제목').fill('Bad link')
  await page.getByLabel('URL').fill('javascript:alert(1)')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toContainText('url: INVALID_URL')
  await expect(page).toHaveURL(/\/resources\/new/)

  await page.getByLabel('URL').fill('https://hibernate.org/orm/documentation/?b=2&a=1')
  await page.getByLabel('제목').fill('Hibernate docs')
  await page.getByLabel('종류').selectOption('DOC')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Hibernate docs' })).toBeVisible()
  // 외부 링크는 새 창임을 글자로 알리고 rel="noopener noreferrer"를 쓴다.
  const link = page.getByRole('link', { name: /hibernate\.org.*새 창/ })
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  await expect(page.getByText('같은 주소의 Resource가 이미 있습니다')).toHaveCount(0)

  // 같은 문서를 다른 표기로 또 저장: 막지 않고 경고한다.
  await page.goto('/resources/new')
  await page.getByLabel('제목').fill('Hibernate docs (다른 맥락)')
  await page.getByLabel('URL').fill('HTTPS://Hibernate.org:443/orm/documentation?utm_source=x&a=1&b=2#top')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Hibernate docs (다른 맥락)' })).toBeVisible()
  await expect(page.getByText('같은 주소의 Resource가 이미 있습니다')).toBeVisible()
  await expect(page.getByRole('status').getByRole('link', { name: 'Hibernate docs' })).toBeVisible()

  await page.goto('/resources?kind=DOC')
  const list = page.getByRole('list', { name: 'Resource 목록' })
  await expect(list.getByRole('listitem')).toHaveCount(1)
  await page.reload()
  await expect(list.getByRole('listitem')).toHaveCount(1)
})

test('project form validates the period and repository URL and can clear dates', async ({ page }) => {
  const token = await apiSignup(page)
  await page.goto('/projects/new')
  await page.getByLabel('제목').fill('Bad project')
  await page.getByLabel('시작일').fill('2026-05-01')
  await page.getByLabel('종료일').fill('2026-04-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toContainText('BEFORE_STARTED_ON')

  await page.getByLabel('종료일').fill('')
  await page.getByLabel('저장소 URL (선택)').fill('javascript:alert(1)')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toContainText('INVALID_URL')

  await page.getByLabel('저장소 URL (선택)').fill('https://github.com/me/p')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Bad project' })).toBeVisible()
  await expect(page.getByText('2026-05-01 ~ 진행 중')).toBeVisible()
  const repo = page.getByRole('link', { name: /github\.com\/me\/p/ })
  await expect(repo).toHaveAttribute('rel', 'noopener noreferrer')

  // 편집에서 날짜를 비우면 지워진다.
  await page.getByRole('link', { name: '편집' }).click()
  await page.getByLabel('시작일').fill('')
  await page.getByLabel('상태').selectOption('COMPLETED')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Bad project' })).toBeVisible()
  await expect(page.getByText('진행 중')).toHaveCount(0)

  // 목록의 상태 필터는 URL에 남는다.
  await api(page, token, '/projects', { title: 'Active one' })
  await page.goto('/projects')
  await expect(page.getByRole('list', { name: 'Project 목록' }).getByRole('listitem')).toHaveCount(2)
  await page.getByRole('group', { name: '상태 필터' }).getByLabel('완료').click()
  await expect(page).toHaveURL(/projectStatus=COMPLETED/)
  await expect(page.getByRole('list', { name: 'Project 목록' }).getByRole('listitem')).toHaveCount(1)
  await expect(page.getByRole('list', { name: 'Project 목록' }).getByText('Bad project')).toBeVisible()
})
