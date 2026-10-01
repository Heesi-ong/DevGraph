import { expect, test, type Locator, type Page } from '@playwright/test'

// Phase 6 완료 조건(설계서 §19, §27.1): 전체 Workflow를 seed 없이 신규 사용자 계정에서 수행한다.
// 14단계 중 Export(Phase 7)를 제외한 1~13을 화면으로 따라간다.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

async function uiSignup(page: Page) {
  const email = `e2e-flow-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  await page.goto('/signup')
  await page.getByLabel('이메일').fill(email)
  await page.getByLabel('표시 이름').fill('Workflow E2E')
  await page.getByLabel('비밀번호(8자 이상)').fill('correct-horse-battery')
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
}

async function quickCreateConcept(page: Page, title: string) {
  await page.goto('/')
  await page.getByLabel('제목').fill(title)
  await page.getByRole('button', { name: '만들기' }).click()
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
}

// 관련 항목의 Relation Picker로 연결한다. side가 INCOMING이면 "대상 → 이 항목" 방향이고 관계 이름은 inverse label이다.
async function linkWithPicker(
  page: Page,
  // pick: 검색어가 여러 후보에 맞을 때 고를 후보 버튼 이름(예: /CONCEPT JPA$/)
  opts: { relation: string; target: string; side?: 'INCOMING'; pick?: RegExp },
) {
  const picker = page.getByRole('region', { name: '관계 추가' })
  if (opts.side === 'INCOMING') await picker.getByLabel('방향').selectOption('INCOMING')
  await picker.getByLabel('관계').selectOption({ label: opts.relation })
  await picker.getByLabel('대상 검색').fill(opts.target)
  await picker.getByRole('list', { name: '연결 후보' }).getByRole('button', { name: opts.pick ?? new RegExp(opts.target) }).click()
  await expect(picker.getByText('연결했습니다')).toBeVisible()
}

// 체인 빌더의 한 단계를 연다: 이름이 `${step} 연결`인 버튼을 눌러 프리셋 Picker에서 대상을 고른다.
async function linkChainStep(page: Page, step: string, target: string) {
  await page.getByRole('button', { name: `${step} 연결` }).click()
  const picker = page.getByRole('region', { name: '관계 추가' })
  await picker.getByLabel('대상 검색').fill(target)
  await picker.getByRole('list', { name: '연결 후보' }).getByRole('button', { name: new RegExp(target) }).click()
  await expect(picker.getByText('연결했습니다')).toBeVisible()
}

async function setEditorCode(page: Page, code: string) {
  const editor = page.getByTestId('code-editor')
  await expect(editor.locator('.monaco-editor')).toBeVisible()
  await editor.locator('.view-lines').click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.insertText(code)
}

async function createSnippet(page: Page, title: string, code: string) {
  await page.goto('/snippets/new')
  await page.getByLabel('제목').fill(title)
  await page.getByLabel('언어').fill('java')
  await setEditorCode(page, code)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
}

function links(list: Locator, name: string) {
  return list.getByRole('link', { name })
}

test('the whole completion workflow runs on a fresh account', async ({ page }) => {
  // 1. 가입하고 개인 Workspace에 진입한다.
  await uiSignup(page)

  // 2~3. Spring Boot, JPA Concept
  await quickCreateConcept(page, 'Spring Boot')
  await quickCreateConcept(page, 'JPA')

  // 4. 두 Node를 RELATED_TO로 연결한다 (Spring Boot 상세에서).
  await page.goto('/library')
  await page.getByRole('link', { name: 'Spring Boot' }).click()
  await linkWithPicker(page, { relation: 'related to', target: 'JPA' })
  await expect(page.getByRole('list', { name: '연결', exact: true }).getByRole('link', { name: 'JPA' })).toBeVisible()

  // 5. JPA Snippet을 등록하고 IS_EXAMPLE_OF → JPA로 연결한다.
  await createSnippet(page, 'JPA repository example', 'public interface OrderRepository extends JpaRepository<Order, Long> {}')
  await linkWithPicker(page, { relation: 'is example of', target: 'JPA' })

  // 6. LazyInitializationException Error를 등록한다.
  await page.goto('/errors/new')
  await page.getByLabel('제목').fill('LazyInitializationException')
  await page.getByLabel('오류 메시지').fill('org.hibernate.LazyInitializationException: could not initialize proxy - no Session')
  await page.getByLabel('환경').fill('Spring Boot 3, Hibernate 6')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'LazyInitializationException' })).toBeVisible()

  // 7. 원인 Concept(Lazy Loading)을 CAUSED_BY로 연결한다. 없으면 먼저 만든다.
  await quickCreateConcept(page, 'Lazy Loading')
  await page.goto('/problems')
  await page.getByRole('link', { name: 'LazyInitializationException' }).click()
  await linkChainStep(page, '원인', 'Lazy Loading')
  await expect(page.getByRole('list', { name: '원인' }).getByRole('link', { name: 'Lazy Loading' })).toBeVisible()

  // 8. Solution을 작성하고 Error와 SOLVED_BY로 연결한다(생성과 동시에).
  await page.getByRole('link', { name: '해결 방법 추가' }).first().click()
  await expect(page.getByLabel('해결하는 Error (선택)')).toContainText('LazyInitializationException')
  await page.getByLabel('제목').fill('서비스 트랜잭션 안에서 fetch join')
  await page.getByLabel('접근 방법 (Markdown)').fill('서비스 메서드의 트랜잭션 경계 안에서 연관 엔티티를 fetch join으로 함께 읽는다.')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: '서비스 트랜잭션 안에서 fetch join' })).toBeVisible()
  await expect(page.getByRole('list', { name: '해결하는 오류' }).getByRole('link', { name: 'LazyInitializationException' })).toBeVisible()

  // 9. 실제 해결 Snippet을 IMPLEMENTED_WITH로 연결한다.
  await createSnippet(page, 'fetch join query', 'select o from Order o join fetch o.items')
  await page.goto('/problems?tab=solutions')
  await page.getByRole('link', { name: '서비스 트랜잭션 안에서 fetch join' }).click()
  await linkChainStep(page, '구현 코드', 'fetch join query')

  // 10. Project를 만들고 지식·오류·해결·Snippet을 연결한다.
  await page.goto('/projects/new')
  await page.getByLabel('제목').fill('TeamFlow')
  await page.getByLabel('설명 (Markdown)').fill('팀 협업 도구 (사이드 프로젝트)')
  await page.getByLabel('저장소 URL (선택)').fill('https://github.com/me/teamflow')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'TeamFlow' })).toBeVisible()
  await linkWithPicker(page, { relation: 'uses', target: 'JPA', pick: /CONCEPT JPA$/, side: 'INCOMING' }) // JPA --USED_IN--> TeamFlow
  await linkWithPicker(page, { relation: 'had occurrence of', target: 'LazyInitializationException', side: 'INCOMING' })
  await linkWithPicker(page, { relation: 'applied from', target: '서비스 트랜잭션', side: 'INCOMING' })
  await linkWithPicker(page, { relation: 'uses', target: 'fetch join query', side: 'INCOMING' })

  // Project 탭: 문제/지식/Snippet이 각자의 탭에 나뉘어 보인다.
  await page.getByRole('tab', { name: 'Problems' }).click()
  await expect(page.getByText('LazyInitializationException')).toBeVisible()
  await expect(page.getByText('서비스 트랜잭션 안에서 fetch join').first()).toBeVisible()
  await page.getByRole('tab', { name: 'Snippets' }).click()
  await expect(page.getByText('fetch join query').first()).toBeVisible()
  await page.getByRole('tab', { name: 'Connected Knowledge' }).click()
  await expect(page.getByText('JPA').first()).toBeVisible()

  // 11. Project Graph와 focus Graph에서 방향과 inverse label을 확인한다.
  await page.getByRole('tab', { name: 'Project Graph' }).click()
  const canvas = page.getByTestId('graph-canvas')
  await expect(canvas.locator('.react-flow__node', { hasText: 'LazyInitializationException' })).toBeVisible()
  await expect(canvas.locator('.react-flow__edge-text', { hasText: 'occurred in' })).toBeVisible()
  await expect(canvas.locator('.react-flow__edge-text', { hasText: 'applied in' })).toBeVisible()
  await page.getByRole('link', { name: '전체 그래프 화면에서 열기' }).click()
  await expect(canvas.locator('.react-flow__node')).not.toHaveCount(0)
  await page.getByRole('tab', { name: '목록' }).click()
  await expect(page.getByRole('region', { name: '관계 목록' })).toContainText('LazyInitializationException')
  await expect(page.getByRole('region', { name: '관계 목록' })).toContainText('occurred in')

  // Error를 해결됨으로 바꾸면 Solution이 연결돼 있으므로 경고가 없다.
  await page.goto('/problems')
  await page.getByRole('link', { name: 'LazyInitializationException' }).click()
  await page.getByLabel('바꿀 상태').selectOption({ label: '해결됨' })
  await page.getByRole('button', { name: '상태 변경' }).click()
  await expect(page.getByTestId('resolution-status')).toHaveText('해결됨')
  await expect(page.getByText('연결된 Solution이 없습니다')).toHaveCount(0)

  // 12. 전역 검색으로 Error와 코드 symbol을 찾아 Snippet 상세에 도달한다.
  await page.keyboard.press('/')
  await page.keyboard.type('LazyInit')
  await page.keyboard.press('Enter')
  const results = page.getByRole('list', { name: '검색 결과' })
  await expect(links(results, 'LazyInitializationException').first()).toBeVisible()
  await expect(results.getByText(/일치: .*오류 메시지/).first()).toBeVisible()
  await page.getByLabel('검색어').fill('OrderRepository')
  await page.getByRole('button', { name: '검색' }).click()
  await links(page.getByRole('list', { name: '검색 결과' }), 'JPA repository example').click()
  await expect(page.getByRole('heading', { name: 'JPA repository example' })).toBeVisible()

  // 13. 코드를 복사하고 최근 사용에 반영된 것을 확인한다.
  await page.getByRole('button', { name: '코드 복사' }).click()
  await expect(page.getByText('복사했습니다')).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
    'public interface OrderRepository extends JpaRepository<Order, Long> {}',
  )
  await expect(page.getByText('v1 · 복사 1회')).toBeVisible()
})
