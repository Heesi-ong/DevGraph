import { expect, test, type Page } from '@playwright/test'

// Phase 3 완료 조건(설계서 §19): v1 생성, 코드 수정 시 v2, metadata 수정 시 불필요한 version 미생성,
// 이전 원문 조회 가능. + 정확한 clipboard 복사, 위험한 내용의 비실행, secret 확인 흐름.
// 백엔드(:8080)가 dev 프로필 + PostgreSQL로 떠 있어야 한다.

test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

async function signup(page: Page) {
  const email = `e2e-snip-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`
  await page.goto('/signup')
  await page.getByLabel('이메일').fill(email)
  await page.getByLabel('표시 이름').fill('Snippet E2E')
  await page.getByLabel('비밀번호(8자 이상)').fill('correct-horse-battery')
  await page.getByRole('button', { name: '가입하기' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
}

// Monaco의 숨은 textarea에 텍스트를 넣는다. 전체 선택 후 입력하면 기존 코드를 대체한다.
async function setEditorCode(page: Page, code: string) {
  const editor = page.getByTestId('code-editor')
  await expect(editor.locator('.monaco-editor')).toBeVisible()
  await editor.locator('.view-lines').click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.insertText(code)
}

const CODE_V1 = 'export function greet(name: string) {\n  return `hello ${name}`\n}\n'
const CODE_V2 = 'export function greet(name: string) {\n  return `hi, ${name}!`\n}\n'

test('Snippet lifecycle: v1, copy exactly, v2 on code change, no version on metadata change, old source', async ({
  page,
}) => {
  await signup(page)

  await page.getByRole('link', { name: 'Snippets' }).click()
  await expect(page.getByText('조건에 맞는 Snippet이 없습니다')).toBeVisible()

  // 생성 → v1
  await page.getByRole('link', { name: '새 Snippet' }).first().click()
  await page.getByLabel('제목').fill('Greet helper')
  await page.getByLabel('언어').fill('typescript')
  await page.getByLabel('Framework (선택)').fill('React')
  await setEditorCode(page, CODE_V1)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Greet helper' })).toBeVisible()
  await expect(page.getByText('v1 · 복사 0회')).toBeVisible()

  // 저장한 원문 그대로 클립보드에 복사된다.
  await page.getByRole('button', { name: '코드 복사' }).click()
  await expect(page.getByText('복사했습니다')).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(CODE_V1)
  await expect(page.getByText('v1 · 복사 1회')).toBeVisible()

  // 코드 수정 → v2
  await page.getByRole('link', { name: '편집' }).click()
  await setEditorCode(page, CODE_V2)
  await page.getByLabel(/변경 요약/).fill('인사말 변경')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByText(/^v2 · 복사 1회/)).toBeVisible()

  // 메타데이터만 수정 → 버전 유지
  await page.getByRole('link', { name: '편집' }).click()
  await expect(page.getByLabel('제목')).toHaveValue('Greet helper')
  await page.getByLabel('제목').fill('Greet helper (renamed)')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Greet helper (renamed)' })).toBeVisible()
  await expect(page.getByText(/^v2 · 복사 1회/)).toBeVisible()

  // 버전 이력: v2(현재), v1. 이전 원문을 볼 수 있고 복사하면 그 원문이 그대로 복사된다.
  const history = page.getByRole('region', { name: '버전 이력' })
  await expect(history.getByText('인사말 변경')).toBeVisible()
  await expect(history.getByRole('button', { name: /원문 보기/ })).toHaveCount(2)
  await history.getByRole('button', { name: 'v1 원문 보기' }).click()
  const v1 = page.getByLabel('v1 원문')
  await expect(v1.getByText('hello ${name}')).toBeVisible()
  await v1.getByRole('button', { name: 'v1 복사' }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(CODE_V1)

  // 이전 버전 복사는 사용 통계에 들어가지 않는다. 새로고침 후에도 v2 / 1회.
  await page.reload()
  await expect(page.getByText(/^v2 · 복사 1회/)).toBeVisible()
})

test('dangerous code is displayed as text and never executed', async ({ page }) => {
  await signup(page)
  const dialogs: string[] = []
  page.on('dialog', async (dialog) => {
    dialogs.push(dialog.message())
    await dialog.dismiss()
  })

  const dangerous = '<img src=x onerror="window.__pwned=1;alert(1)">\n<script>window.__pwned=1;alert(2)</script>\n'
  await page.goto('/snippets/new')
  await page.getByLabel('제목').fill('Dangerous html')
  await page.getByLabel('언어').fill('html')
  await setEditorCode(page, dangerous)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Dangerous html' })).toBeVisible()

  const block = page.getByLabel('html 코드')
  await expect(block).toContainText('onerror="window.__pwned=1;alert(1)"')
  await expect(block).toContainText('<script>window.__pwned=1;alert(2)</script>')
  // 코드가 DOM 요소로 해석되지 않았다.
  expect(await block.locator('img, script').count()).toBe(0)
  expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined()
  expect(dialogs).toEqual([])
})

test('secret warning requires confirmation and never blocks the fix path', async ({ page }) => {
  await signup(page)
  await page.goto('/snippets/new')
  await page.getByLabel('제목').fill('Config sample')
  await page.getByLabel('언어').fill('bash')
  await setEditorCode(page, 'export DB_PASSWORD="hunter2hunter2"\n')
  await page.getByRole('button', { name: '저장' }).click()

  const warning = page.getByRole('alert', { name: 'secret 의심' })
  await expect(warning).toBeVisible()
  await expect(warning).toContainText('1번째 줄')
  await expect(warning).not.toContainText('hunter2hunter2')
  await expect(page.getByRole('button', { name: '확인하고 저장' })).toBeDisabled()

  // 코드를 고치면 경고가 사라지고 일반 저장으로 돌아간다(수정 경로).
  await setEditorCode(page, 'export DB_PASSWORD="${DB_PASSWORD}"\n')
  await expect(warning).toHaveCount(0)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('heading', { name: 'Config sample' })).toBeVisible()
  await expect(page.getByText('secret으로 의심되는 값을 확인하고 저장한')).toHaveCount(0)

  // 확인 후 저장하는 경로
  await page.goto('/snippets/new')
  await page.getByLabel('제목').fill('Known example')
  await page.getByLabel('언어').fill('bash')
  await setEditorCode(page, 'API_TOKEN=abcd1234efgh5678\n')
  await page.getByRole('button', { name: '저장' }).click()
  await page.getByLabel('secret이 아니거나, 알고도 저장합니다').check()
  await page.getByRole('button', { name: '확인하고 저장' }).click()
  await expect(page.getByRole('heading', { name: 'Known example' })).toBeVisible()
  await expect(page.getByText('secret으로 의심되는 값을 확인하고 저장한')).toBeVisible()
})

test('snippet list filters live in the URL and survive reload', async ({ page }) => {
  await signup(page)
  for (const [title, language] of [
    ['Py one', 'python'],
    ['Go one', 'go'],
  ]) {
    await page.goto('/snippets/new')
    await page.getByLabel('제목').fill(title)
    await page.getByLabel('언어').fill(language)
    await setEditorCode(page, `// ${title}\n`)
    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
  }

  await page.goto('/snippets')
  await expect(page.getByText('Py one')).toBeVisible()
  await expect(page.getByText('Go one')).toBeVisible()

  await page.goto('/snippets?language=python')
  await expect(page.getByText('Py one')).toBeVisible()
  await expect(page.getByText('Go one')).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('Py one')).toBeVisible()
  await expect(page.getByText('Go one')).toHaveCount(0)

  // 보관하면 기본 목록에서 빠지고 status=ARCHIVED 목록에서 보인다.
  await page.getByRole('link', { name: 'Py one' }).click()
  await page.getByRole('button', { name: '보관', exact: true }).click()
  await expect(page.getByText('상태: 보관됨')).toBeVisible()
  // 없음을 확인하기 전에 목록이 실제로 로드됐음(빈 상태 문구)을 먼저 기다린다. 아니면 로딩 중에도 통과해 버리고,
  // 곧바로 이동하면서 진행 중인 요청(refresh 포함)을 끊는다.
  await page.goto('/snippets?language=python')
  await expect(page.getByText('조건에 맞는 Snippet이 없습니다')).toBeVisible()
  await expect(page.getByText('Py one')).toHaveCount(0)
  await page.goto('/snippets?language=python&status=ARCHIVED')
  await expect(page.getByText('Py one')).toBeVisible()
})
