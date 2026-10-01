import { expect, test, type Page } from '@playwright/test'

// Phase 5 완료 조건(설계서 §19): 제목 exact 우선 등 랭킹은 백엔드 fixture 테스트가 검증한다. 여기서는 화면 흐름 —
// 전역 검색과 단축키, 결과·일치 필드·하이라이트, 키보드 탐색, 2자 미만 입력, 필터의 URL 유지, 0건 안내, 더 보기.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

interface Session {
  token: string
}

async function apiSignup(page: Page): Promise<Session> {
  const email = `e2e-search-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  const res = await page.request.post('/api/v1/auth/signup', {
    data: { email, displayName: 'Search E2E', password: 'correct-horse-battery' },
  })
  expect(res.status()).toBe(201)
  return { token: (await res.json()).accessToken }
}

async function api(page: Page, s: Session, path: string, data: unknown) {
  const res = await page.request.post(`/api/v1${path}`, { headers: { Authorization: `Bearer ${s.token}` }, data })
  expect(res.ok(), `${path} -> ${res.status()}`).toBe(true)
  return res.json()
}

const concept = (page: Page, s: Session, title: string, extra: object = {}) =>
  api(page, s, '/nodes', { type: 'CONCEPT', title, ...extra })
const snippet = (page: Page, s: Session, title: string, code: string, language = 'java') =>
  api(page, s, '/snippets', { title, language, code })

test('global search: shortcut, ranked results, matched fields and highlight, open a result', async ({ page }) => {
  const s = await apiSignup(page)
  await concept(page, s, 'JWT', { summary: 'JSON Web Token' })
  await snippet(page, s, 'Auth filter', 'public class JwtAuthenticationFilter extends OncePerRequestFilter {}')
  await api(page, s, '/nodes', { type: 'NOTE', title: '회의 메모', bodyMd: '오늘은 JWT 만료 시간을 논의했다' })

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  // `/` 단축키로 전역 검색창에 포커스
  await page.keyboard.press('/')
  await expect(page.getByLabel('전역 검색')).toBeFocused()
  await page.keyboard.type('jwt')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/search\?q=jwt/)

  const results = page.getByRole('list', { name: '검색 결과' })
  await expect(results.getByRole('listitem')).toHaveCount(3)
  // 제목이 정확히 같은 항목이 맨 위
  await expect(results.getByRole('listitem').first().getByRole('link')).toHaveText('JWT')
  await expect(results.getByRole('listitem').first().locator('mark')).toHaveText('JWT')
  // 본문 일치는 발췌와 일치 필드로, 코드 일치는 코드 발췌로 보인다.
  const note = results.getByRole('listitem').filter({ hasText: '회의 메모' })
  await expect(note.getByText('일치: 본문')).toBeVisible()
  await expect(note.locator('mark')).toHaveText('JWT')
  const code = results.getByRole('listitem').filter({ hasText: 'Auth filter' })
  await expect(code.getByText(/일치: .*코드/)).toBeVisible()
  await expect(code.locator('pre mark')).toHaveText('Jwt')

  await results.getByRole('link', { name: '회의 메모' }).click()
  await expect(page.getByRole('heading', { name: '회의 메모' })).toBeVisible()
})

test('results can be walked and opened with the keyboard only', async ({ page }) => {
  const s = await apiSignup(page)
  await concept(page, s, 'Kotlin coroutines')
  await concept(page, s, 'Kotlin flow')

  await page.goto('/search?q=kotlin')
  const links = page.getByRole('list', { name: '검색 결과' }).getByRole('link')
  await expect(links).toHaveCount(2)
  await page.getByLabel('검색어').focus()
  await page.keyboard.press('ArrowDown')
  await expect(links.nth(0)).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(links.nth(1)).toBeFocused()
  await page.keyboard.press('ArrowUp')
  await expect(links.nth(0)).toBeFocused()
  const opened = (await links.nth(0).textContent()) ?? ''
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: opened })).toBeVisible()
})

test('global search and history navigation keep the page input synchronized', async ({ page }) => {
  const s = await apiSignup(page)
  await concept(page, s, 'Kotlin flow')
  await concept(page, s, 'Docker basics')
  await page.goto('/search?q=kotlin')
  await expect(page.getByLabel('검색어', { exact: true })).toHaveValue('kotlin')
  await page.getByLabel('전역 검색').fill('docker')
  await page.getByLabel('전역 검색').press('Enter')
  await expect(page.getByLabel('검색어', { exact: true })).toHaveValue('docker')
  await expect(page.getByRole('list', { name: '검색 결과' })).toContainText('Docker basics')
  await page.goBack()
  await expect(page.getByLabel('검색어', { exact: true })).toHaveValue('kotlin')
  await page.goForward()
  await expect(page.getByLabel('검색어', { exact: true })).toHaveValue('docker')
})

test('input shorter than two characters (or Korean jamo only) never reaches the server and shows recent items', async ({
  page,
}) => {
  const s = await apiSignup(page)
  await concept(page, s, 'Recently made')
  const searchCalls: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/search')) searchCalls.push(request.url())
  })

  for (const q of ['a', 'ㅅㅅ', '']) {
    await page.goto(`/search?q=${encodeURIComponent(q)}`)
    await expect(page.getByRole('region', { name: '검색 시작' })).toBeVisible()
    await expect(page.getByRole('list', { name: '최근 항목' }).getByText('Recently made')).toBeVisible()
  }
  await page.goto('/search?q=a')
  await expect(page.getByText('검색어는 2자 이상 입력해 주세요.')).toBeVisible()
  expect(searchCalls).toEqual([])

  // 검색하면 최근 검색어에 남고, 눌러 다시 검색할 수 있다.
  await page.getByLabel('검색어').fill('recently')
  await page.getByRole('button', { name: '검색' }).click()
  await expect(page.getByRole('list', { name: '검색 결과' }).getByText('Recently made')).toBeVisible()
  await page.goto('/search')
  await page.getByRole('list', { name: '최근 검색어' }).getByRole('button', { name: 'recently' }).click()
  await expect(page).toHaveURL(/q=recently/)
  await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()
})

test('filters live in the URL, zero results explain the filters, and more results load', async ({ page }) => {
  const s = await apiSignup(page)
  await concept(page, s, 'Docker basics')
  await snippet(page, s, 'Docker run script', 'docker run -d nginx', 'bash')
  for (let i = 10; i < 35; i++) await concept(page, s, `Bulk item ${i}`)

  await page.goto('/search?q=docker&types=SNIPPET')
  const results = page.getByRole('list', { name: '검색 결과' })
  await expect(results.getByRole('listitem')).toHaveCount(1)
  await expect(results.getByText('Docker run script')).toBeVisible()
  await page.reload()
  await expect(results.getByRole('listitem')).toHaveCount(1)

  // 결과가 없는 필터 조합: 필터를 풀면 몇 건인지 알려 주고, 눌러서 해제한다.
  await page.goto('/search?q=docker&types=NOTE')
  const none = page.getByRole('region', { name: '결과 없음' })
  await expect(none.getByText('필터를 풀면 2건이 있습니다.')).toBeVisible()
  await none.getByRole('button', { name: '필터 해제' }).click()
  await expect(results.getByRole('listitem')).toHaveCount(2)

  // 더 보기: 20건씩
  await page.goto('/search?q=bulk%20item')
  await expect(results.getByRole('listitem')).toHaveCount(20)
  await page.getByRole('button', { name: '더 보기' }).click()
  await expect(results.getByRole('listitem')).toHaveCount(25)
  await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0)

  // 오타는 유사 결과를 제안한다.
  await page.goto('/search?q=dockr%20basic')
  await expect(page.getByRole('list', { name: '유사 결과' }).getByText('Docker basics')).toBeVisible()
})

test('search results show dangerous code as plain text and never execute it', async ({ page }) => {
  const s = await apiSignup(page)
  const dialogs: string[] = []
  page.on('dialog', async (dialog) => {
    dialogs.push(dialog.message())
    await dialog.dismiss()
  })
  await snippet(page, s, 'Payload sample', '<img src=x onerror="window.__pwned=1;alert(1)">', 'html')

  await page.goto('/search?q=onerror')
  const code = page.getByRole('list', { name: '검색 결과' }).locator('pre')
  await expect(code).toContainText('<img src=x onerror="window.__pwned=1;alert(1)">')
  await expect(code.locator('mark')).toHaveText('onerror')
  expect(await page.locator('main img').count()).toBe(0)
  expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined()
  expect(dialogs).toEqual([])
})
