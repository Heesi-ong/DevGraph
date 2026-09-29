import { expect, test, type Page } from '@playwright/test'

// Phase 4 완료 조건(설계서 §19): 학습 시나리오(Concept ↔ Snippet 연결, backlink, 그래프 탐색)가 전체 동작하고
// cycle graph에서도 응답 상한을 지킨다. keyboard graph alternative(목록)도 검증한다.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

interface Session {
  token: string
}

// 화면으로 가입하면 느리므로 API로 가입한다. 응답의 refresh/csrf 쿠키가 브라우저 context에 들어가
// 페이지를 열 때 앱이 세션을 복구한다(새로고침 시나리오와 같다).
async function apiSignup(page: Page): Promise<Session> {
  const email = `e2e-rel-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  const res = await page.request.post('/api/v1/auth/signup', {
    data: { email, displayName: 'Relation E2E', password: 'correct-horse-battery' },
  })
  expect(res.status()).toBe(201)
  return { token: (await res.json()).accessToken }
}

async function api(page: Page, session: Session, method: 'post' | 'get', path: string, data?: unknown) {
  const res = await page.request[method](`/api/v1${path}`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data,
  })
  expect(res.ok(), `${method} ${path} -> ${res.status()}`).toBe(true)
  return res.json()
}

async function typeIds(page: Page, session: Session): Promise<Record<string, string>> {
  const types = (await api(page, session, 'get', '/relation-types')) as { id: string; key: string }[]
  return Object.fromEntries(types.map((t) => [t.key, t.id]))
}

const concept = async (page: Page, s: Session, title: string) =>
  (await api(page, s, 'post', '/nodes', { type: 'CONCEPT', title })).id as string
const snippet = async (page: Page, s: Session, title: string) =>
  (await api(page, s, 'post', '/snippets', { title, language: 'java', code: 'class A {}' })).id as string
const relate = (page: Page, s: Session, source: string, target: string, relationTypeId: string) =>
  api(page, s, 'post', '/relations', { sourceNodeId: source, targetNodeId: target, relationTypeId })

test('learning scenario: link a snippet to a concept, see the backlink, change and delete the relation', async ({
  page,
}) => {
  const s = await apiSignup(page)
  const jwt = await concept(page, s, 'JWT')
  await concept(page, s, 'Spring Security')
  const filter = await snippet(page, s, 'JWT filter')

  // Snippet 상세에서 "is example of → JWT" 연결. 후보는 허용 Target(Concept)만이다.
  await page.goto(`/snippets/${filter}`)
  const picker = page.getByRole('region', { name: '관계 추가' })
  await picker.getByLabel('관계').selectOption({ label: 'is example of' })
  await picker.getByLabel('대상 검색').fill('jwt')
  const candidates = picker.getByRole('list', { name: '연결 후보' })
  await expect(candidates.getByRole('button')).toHaveCount(1)
  await expect(candidates.getByRole('button', { name: /JWT/ })).toBeVisible()
  await picker.getByLabel('메모 (선택)').fill('필터 체인 예시')
  await candidates.getByRole('button', { name: /JWT/ }).click()

  const links = page.getByRole('list', { name: '연결', exact: true })
  await expect(links.locator('.badge', { hasText: 'is example of' })).toBeVisible()
  await expect(links.getByRole('link', { name: 'JWT' })).toBeVisible()
  await expect(links.getByText('필터 체인 예시')).toBeVisible()
  // 이미 연결된 대상은 후보에서 빠진다.
  await picker.getByLabel('대상 검색').fill('jwt')
  await expect(picker.getByText('연결할 수 있는 항목이 없습니다.')).toBeVisible()

  // Concept 쪽에는 backlink(inverse label)로 보인다. 링크로 Snippet 화면에 갈 수 있다.
  await page.goto(`/nodes/${jwt}`)
  const backlinks = page.getByRole('list', { name: '백링크' })
  await expect(backlinks.locator('.badge', { hasText: 'has example' })).toBeVisible()
  await backlinks.getByRole('link', { name: 'JWT filter' }).click()
  await expect(page.getByRole('heading', { name: 'JWT filter' })).toBeVisible()

  // Concept에서 반대 방향(대상 → 이 항목)으로도 연결할 수 있다: Spring Security depends on… 는 Concept→Concept.
  await page.goto(`/nodes/${jwt}`)
  const conceptPicker = page.getByRole('region', { name: '관계 추가' })
  await conceptPicker.getByLabel('관계').selectOption({ label: 'depends on' })
  await conceptPicker.getByLabel('대상 검색').fill('spring')
  await conceptPicker.getByRole('list', { name: '연결 후보' }).getByRole('button', { name: /Spring Security/ }).click()
  const outgoing = page.getByRole('list', { name: '연결', exact: true })
  await expect(outgoing.locator('.badge', { hasText: 'depends on' })).toBeVisible()

  // 타입 변경(허용 조합 안에서) → 삭제
  await outgoing.getByLabel('Spring Security 관계 타입 변경').selectOption({ label: 'is part of' })
  await expect(outgoing.locator('.badge', { hasText: 'is part of' })).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await outgoing.getByRole('button', { name: '관계 삭제' }).click()
  await expect(page.getByRole('list', { name: '연결', exact: true })).toHaveCount(0)
  await expect(page.getByText('아직 연결된 항목이 없습니다.')).toHaveCount(0) // backlink가 남아 있으므로 빈 상태가 아니다
  await expect(page.getByRole('list', { name: '백링크' })).toBeVisible()
})

test('graph: focus by depth, filters live in the URL, list alternative and drawer work with the keyboard', async ({
  page,
}) => {
  const s = await apiSignup(page)
  const t = await typeIds(page, s)
  const a = await concept(page, s, 'Alpha')
  const b = await concept(page, s, 'Bravo')
  const c = await concept(page, s, 'Charlie')
  const sn = await snippet(page, s, 'Delta snippet')
  await relate(page, s, a, b, t.IS_PART_OF)
  await relate(page, s, b, c, t.IS_PART_OF)
  await relate(page, s, sn, a, t.IMPLEMENTS)

  // 상세의 "그래프로 보기" → 중심 그래프(depth 1): Alpha, Bravo, Delta
  await page.goto(`/nodes/${a}`)
  await page.getByRole('link', { name: '그래프로 보기' }).click()
  await expect(page).toHaveURL(new RegExp(`/graph\\?focus=${a}`))
  const canvas = page.getByTestId('graph-canvas')
  await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
  await expect(canvas.locator('.react-flow__node', { hasText: 'Alpha' })).toBeVisible()
  await expect(canvas.locator('.react-flow__node', { hasText: 'Charlie' })).toHaveCount(0)
  await expect(canvas.locator('.react-flow__edge-text', { hasText: 'is part of' })).toBeVisible()

  // depth 2 → Charlie 포함. 필터는 URL에 남고 새로고침해도 유지된다.
  await page.getByLabel('깊이').selectOption('2')
  await expect(page).toHaveURL(/depth=2/)
  await expect(canvas.locator('.react-flow__node')).toHaveCount(4)
  await page.reload()
  await expect(canvas.locator('.react-flow__node')).toHaveCount(4)

  // 노드를 누르면 이동하지 않고 drawer가 열린다. 이동은 "상세 보기"로만.
  await canvas.locator('.react-flow__node', { hasText: 'Charlie' }).click()
  const drawer = page.getByRole('complementary', { name: '항목 상세' })
  await expect(drawer.getByRole('heading', { name: 'Charlie' })).toBeVisible()
  await expect(page).toHaveURL(/\/graph\?/)
  await drawer.getByRole('link', { name: '상세 보기' }).click()
  await expect(page.getByRole('heading', { name: 'Charlie' })).toBeVisible()
  await expect(page).toHaveURL(/\/nodes\//)

  // 키보드 대안: 목록 보기에서 Tab/Enter만으로 항목을 고르고, 그 항목을 중심으로 다시 본다.
  await page.goto(`/graph?focus=${a}&depth=2`)
  await page.getByRole('tab', { name: '목록' }).click()
  const bravo = page.getByRole('region', { name: '항목 목록' }).getByRole('button', { name: /Bravo/ })
  await bravo.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('complementary', { name: '항목 상세' }).getByRole('heading', { name: 'Bravo' })).toBeVisible()
  await expect(page.getByRole('region', { name: '관계 목록' })).toContainText('Alpha')
  await page.getByRole('button', { name: '이 항목 중심으로 보기' }).click()
  await expect(page).toHaveURL(new RegExp(`focus=${b}`))

  // 유형 필터: Snippet만
  await page.goto(`/graph?focus=${a}`)
  // URL이 상태의 원천이라 체크 표시는 라우터 갱신 뒤에 바뀐다. check()는 즉시 검사하므로 click 후 상태를 기다린다.
  const snippetType = page.getByRole('group', { name: '항목 유형' }).getByLabel('SNIPPET')
  await snippetType.click()
  await expect(snippetType).toBeChecked()
  await expect(page).toHaveURL(/nodeTypes=SNIPPET/)
  await expect(canvas.locator('.react-flow__node')).toHaveCount(2) // Alpha(중심) + Delta
  await page.getByRole('button', { name: '중심 해제' }).click()
  await expect(page).not.toHaveURL(/focus=/)
})

test('cycle graph terminates within its limits and shows each item once', async ({ page }) => {
  const s = await apiSignup(page)
  const t = await typeIds(page, s)
  const a = await concept(page, s, 'Cycle A')
  const b = await concept(page, s, 'Cycle B')
  const c = await concept(page, s, 'Cycle C')
  await relate(page, s, a, b, t.DEPENDS_ON)
  await relate(page, s, b, c, t.DEPENDS_ON)
  await relate(page, s, c, a, t.DEPENDS_ON)

  await page.goto(`/graph?focus=${a}&depth=3`)
  const canvas = page.getByTestId('graph-canvas')
  await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
  await expect(canvas.locator('.react-flow__edge')).toHaveCount(3)
  await expect(page.getByRole('status').filter({ hasText: '일부만 표시' })).toHaveCount(0)

  // Workspace 전체 그래프에서도 같다.
  await page.goto('/graph')
  await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
  await expect(canvas.locator('.react-flow__edge')).toHaveCount(3)
})

test('trashed and archived items leave the graph unless archived items are requested', async ({ page }) => {
  const s = await apiSignup(page)
  const t = await typeIds(page, s)
  const a = await concept(page, s, 'Keep')
  const b = await concept(page, s, 'Archive me')
  await relate(page, s, a, b, t.RELATED_TO)
  await api(page, s, 'post', `/nodes/${b}/archive`, { version: 0 })

  await page.goto(`/graph?focus=${a}`)
  const canvas = page.getByTestId('graph-canvas')
  await expect(canvas.locator('.react-flow__node')).toHaveCount(1)
  const includeArchived = page.getByLabel('보관된 항목 포함')
  await includeArchived.click()
  await expect(includeArchived).toBeChecked()
  await expect(page).toHaveURL(/archived=true/)
  await expect(canvas.locator('.react-flow__node')).toHaveCount(2)
  await expect(canvas.locator('.react-flow__node', { hasText: 'Archive me' })).toBeVisible()
})
