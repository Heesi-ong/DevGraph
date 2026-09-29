import { useMutation, useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useRef, useState, type FormEvent } from 'react'
import { createSnippet, getSnippet, updateSnippet } from '../../entities/snippet/api'
import { snippetKeys } from '../../entities/snippet/queryKeys'
import type { SecretConfirmation, SnippetDetail } from '../../entities/snippet/types'
import type { Tag } from '../../entities/tag/types'
import { toApiError, type ApiErrorInfo } from '../../shared/api/errors'
import { COMMON_LANGUAGES } from '../../shared/editor/languages'
import { useUnsavedChangesWarning } from '../edit-node/useUnsavedChangesWarning'
import { TagPicker } from '../tags/TagPicker'

// Monaco는 크다. 편집 화면이 열릴 때만 내려받는다(§16.4).
const CodeEditor = lazy(() => import('../../shared/editor/CodeEditor'))

interface Props {
  mode: 'create' | 'edit'
  initial?: SnippetDetail
  onSaved: (snippet: SnippetDetail) => void
  onCancel: () => void
}

const SECRET_LABELS: Record<string, string> = {
  PRIVATE_KEY: 'private key',
  API_KEY: 'API key 형태의 값',
  JWT: 'JWT 형태의 값',
  CREDENTIAL_ASSIGNMENT: 'password/token 등에 값이 대입된 코드',
  CONNECTION_STRING: '계정 정보가 들어 있는 접속 문자열',
}

// 서버는 fieldErrors에 "KIND@L줄번호"만 준다. 의심 값 자체는 오지 않는다.
function parseFindings(error: ApiErrorInfo) {
  return error.fieldErrors
    .filter((f) => f.field === 'code')
    .map((f) => {
      const [kind, line] = f.reason.split('@L')
      return { kind, line: Number(line) }
    })
}

export function SnippetForm({ mode, initial, onSaved, onCancel }: Props) {
  const queryClient = useQueryClient()
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [language, setLanguage] = useState(initial?.snippet.language ?? 'typescript')
  const [framework, setFramework] = useState(initial?.snippet.framework ?? '')
  const [code, setCode] = useState(initial?.currentVersion.code ?? '')
  const [changeSummary, setChangeSummary] = useState('')
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])
  const [version, setVersion] = useState(initial?.version ?? 0)
  const [error, setError] = useState<ApiErrorInfo | null>(null)
  const [conflict, setConflict] = useState<SnippetDetail | null>(null)
  // 위험 확인은 "지금 보이는 의심 목록"에 대한 것이다. 코드를 고치면 다시 받아야 하므로 확인 상태를 초기화한다.
  const [confirmed, setConfirmed] = useState(false)
  const [confirmedHighRisk, setConfirmedHighRisk] = useState(false)

  const baseline = useRef({
    title: initial?.title ?? '',
    summary: initial?.summary ?? '',
    language: initial?.snippet.language ?? 'typescript',
    framework: initial?.snippet.framework ?? '',
    code: initial?.currentVersion.code ?? '',
    tagIds: (initial?.tags ?? []).map((t) => t.id).sort().join(','),
  })
  const allowLeave = useRef(false)
  const isDirty = () =>
    !allowLeave.current &&
    (title !== baseline.current.title ||
      summary !== baseline.current.summary ||
      language !== baseline.current.language ||
      framework !== baseline.current.framework ||
      code !== baseline.current.code ||
      tags.map((t) => t.id).sort().join(',') !== baseline.current.tagIds)
  useUnsavedChangesWarning(isDirty)

  const findings = error?.code === 'SECRET_CONFIRMATION_REQUIRED' ? parseFindings(error) : []
  const highRisk = findings.some((f) => f.kind === 'PRIVATE_KEY')
  const codeChanged = mode === 'edit' && code !== baseline.current.code

  const save = useMutation({
    mutationFn: (args: { versionToSend: number; confirmation?: SecretConfirmation }) => {
      const input = {
        title,
        summary,
        language,
        framework,
        code,
        changeSummary: codeChanged ? changeSummary : undefined,
        tagIds: tags.map((t) => t.id),
        secretConfirmation: args.confirmation,
      }
      return mode === 'create' ? createSnippet(input) : updateSnippet(initial!.id, args.versionToSend, input)
    },
    onSuccess: async (snippet) => {
      allowLeave.current = true
      queryClient.setQueryData(snippetKeys.detail(snippet.id), snippet)
      await queryClient.invalidateQueries({ queryKey: snippetKeys.lists() })
      await queryClient.invalidateQueries({ queryKey: snippetKeys.versions(snippet.id) })
      onSaved(snippet)
    },
    onError: async (err) => {
      const info = toApiError(err)
      if (info.code === 'VERSION_CONFLICT' && initial) {
        try {
          setConflict(await getSnippet(initial.id))
          setError(null)
          return
        } catch {
          // 최신 내용을 못 가져오면 일반 오류로 알린다.
        }
      }
      setError(info)
    },
  })

  function currentConfirmation(): SecretConfirmation | undefined {
    if (!error || error.code !== 'SECRET_CONFIRMATION_REQUIRED') return undefined
    if (highRisk) return confirmedHighRisk ? 'CONFIRMED_HIGH_RISK' : undefined
    return confirmed ? 'CONFIRMED' : undefined
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    save.mutate({ versionToSend: version, confirmation: currentConfirmation() })
  }

  function changeCode(next: string) {
    setCode(next)
    if (error?.code === 'SECRET_CONFIRMATION_REQUIRED') {
      setError(null)
      setConfirmed(false)
      setConfirmedHighRisk(false)
    }
  }

  function overwriteWithMine() {
    if (!conflict) return
    setVersion(conflict.version)
    setConflict(null)
    save.mutate({ versionToSend: conflict.version })
  }

  function replaceWithLatest() {
    if (!conflict) return
    setTitle(conflict.title)
    setSummary(conflict.summary ?? '')
    setLanguage(conflict.snippet.language)
    setFramework(conflict.snippet.framework ?? '')
    setCode(conflict.currentVersion.code)
    setTags(conflict.tags)
    setVersion(conflict.version)
    baseline.current = {
      title: conflict.title,
      summary: conflict.summary ?? '',
      language: conflict.snippet.language,
      framework: conflict.snippet.framework ?? '',
      code: conflict.currentVersion.code,
      tagIds: conflict.tags.map((t) => t.id).sort().join(','),
    }
    setConflict(null)
  }

  const fieldError = (field: string) => error?.fieldErrors.find((f) => f.field === field)
  const generalError = error && error.code !== 'SECRET_CONFIRMATION_REQUIRED' && error.fieldErrors.length === 0

  return (
    <form onSubmit={handleSubmit} className="form">
      {conflict && (
        <section role="alert" className="conflict">
          <h2>다른 곳에서 먼저 수정되었습니다</h2>
          <p>
            입력한 내용은 그대로 유지되어 있습니다. 최신 버전은 v{conflict.snippet.currentVersionNo}이고 제목은 “
            {conflict.title}”입니다.
          </p>
          <button type="button" onClick={overwriteWithMine}>
            내 입력으로 덮어쓰기
          </button>{' '}
          <button type="button" onClick={replaceWithLatest}>
            최신 내용으로 교체
          </button>
        </section>
      )}

      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </label>
      {fieldError('title') && <p role="alert" className="error-text">제목은 1~200자여야 합니다.</p>}

      <label>
        설명
        <textarea value={summary} onChange={(e) => setSummary(e.target.value)} rows={2} maxLength={1000} />
      </label>

      <div className="row">
        <label>
          언어
          <input
            list="snippet-languages"
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            required
            maxLength={30}
          />
        </label>
        <datalist id="snippet-languages">
          {COMMON_LANGUAGES.map((l) => (
            <option key={l} value={l} />
          ))}
        </datalist>
        <label>
          Framework (선택)
          <input value={framework} onChange={(e) => setFramework(e.target.value)} maxLength={50} />
        </label>
      </div>
      {fieldError('language') && <p role="alert" className="error-text">언어는 영문 소문자·숫자·+#._- 조합이어야 합니다.</p>}

      <div>
        <span id="code-label">코드</span>
        <Suspense fallback={<p role="status" className="muted">에디터를 불러오는 중...</p>}>
          <CodeEditor value={code} language={language} onChange={changeCode} ariaLabel="코드" />
        </Suspense>
        {fieldError('code') && error?.code !== 'SECRET_CONFIRMATION_REQUIRED' && (
          <p role="alert" className="error-text">
            {error?.code === 'PAYLOAD_TOO_LARGE'
              ? '코드는 한 버전당 512KB까지 저장할 수 있습니다.'
              : '코드를 입력해 주세요(NUL 문자와 깨진 문자는 사용할 수 없습니다).'}
          </p>
        )}
      </div>

      {codeChanged && (
        <label>
          변경 요약 (선택, 새 버전에 기록됩니다)
          <input value={changeSummary} onChange={(e) => setChangeSummary(e.target.value)} maxLength={200} />
        </label>
      )}

      <TagPicker selected={tags} onChange={setTags} />

      {findings.length > 0 && (
        <section role="alert" className="conflict" aria-label="secret 의심">
          <h2>secret으로 보이는 값이 있습니다</h2>
          <ul>
            {findings.map((f, i) => (
              <li key={i}>
                {f.line}번째 줄: {SECRET_LABELS[f.kind] ?? f.kind}
              </li>
            ))}
          </ul>
          <p className="muted">
            코드를 수정하면 이 목록이 사라집니다. 예시 값이거나 그대로 저장해야 한다면 아래에서 확인해 주세요.
            의심 값은 서버 로그에 남기지 않습니다.
          </p>
          {!highRisk && (
            <label className="check">
              <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
              secret이 아니거나, 알고도 저장합니다
            </label>
          )}
          {highRisk && (
            <label className="check">
              <input
                type="checkbox"
                checked={confirmedHighRisk}
                onChange={(e) => setConfirmedHighRisk(e.target.checked)}
              />
              private key가 그대로 저장되며 복사·Export에 포함된다는 것을 다시 확인합니다
            </label>
          )}
        </section>
      )}

      {generalError && (
        <p role="alert" className="error-text">
          {error.message}
        </p>
      )}

      <div className="row">
        <button
          type="submit"
          disabled={
            save.isPending ||
            !title.trim() ||
            !code.trim() ||
            (findings.length > 0 && (highRisk ? !confirmedHighRisk : !confirmed))
          }
        >
          {save.isPending ? '저장 중...' : findings.length > 0 ? '확인하고 저장' : '저장'}
        </button>
        <button type="button" onClick={onCancel}>
          취소
        </button>
      </div>
    </form>
  )
}
