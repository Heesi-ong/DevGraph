import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { changePassword, listSessions, requestDeletion, revokeSession } from '../features/account/api'
import { useAuthStore } from '../features/auth/authStore'
import { createExport, downloadPath, getExport, recentExports, type ExportJob } from '../features/export/api'
import { ReauthDialog } from '../features/reauth/ReauthDialog'
import { toApiError } from '../shared/api/errors'
import { formatDateTime } from '../shared/format/datetime'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'

// 설계서 §19 Phase 7 settings: 계정/보안(비밀번호, 세션), 데이터(Export), 위험 구역(탈퇴).

function PasswordSection() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [revokeOthers, setRevokeOthers] = useState(true)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const queryClient = useQueryClient()

  const change = useMutation({
    mutationFn: () => changePassword(current, next, revokeOthers),
    onSuccess: () => {
      setCurrent('')
      setNext('')
      setMessage({ ok: true, text: '비밀번호를 변경했습니다.' })
      void queryClient.invalidateQueries({ queryKey: ['sessions'] })
    },
    onError: (err) => {
      const info = toApiError(err)
      setMessage({
        ok: false,
        text:
          info.status === 401
            ? '현재 비밀번호가 올바르지 않습니다.'
            : info.status === 429
              ? '시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.'
              : info.fieldErrors[0]
                ? '새 비밀번호가 규칙(8자 이상)에 맞지 않습니다.'
                : info.message,
      })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    setMessage(null)
    change.mutate()
  }

  return (
    <section aria-labelledby="pw-heading">
      <h2 id="pw-heading">비밀번호 변경</h2>
      <form className="form" onSubmit={submit}>
        <label>
          현재 비밀번호
          <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
        </label>
        <label>
          새 비밀번호(8자 이상)
          <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={8} required />
        </label>
        <label className="check">
          <input type="checkbox" checked={revokeOthers} onChange={(e) => setRevokeOthers(e.target.checked)} />
          다른 기기에서 로그아웃
        </label>
        {message && (
          <p role={message.ok ? 'status' : 'alert'} className={message.ok ? undefined : 'error-text'}>
            {message.text}
          </p>
        )}
        <button type="submit" disabled={change.isPending}>
          {change.isPending ? '변경 중...' : '비밀번호 변경'}
        </button>
      </form>
    </section>
  )
}

function SessionsSection() {
  const queryClient = useQueryClient()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: ['sessions'], queryFn: listSessions })
  const revoke = useMutation({
    mutationFn: revokeSession,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sessions'] }),
  })

  return (
    <section aria-labelledby="sessions-heading">
      <h2 id="sessions-heading">로그인된 기기</h2>
      {isLoading && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => refetch()} />}
      {revoke.error && (
        <p role="alert" className="error-text">
          {toApiError(revoke.error).message}
        </p>
      )}
      {data && (
        <ul className="node-list" aria-label="세션 목록">
          {data.map((s) => (
            <li key={s.id} className="node-card row">
              <span className="node-title">
                {s.deviceLabel ?? '알 수 없는 기기'}
                {s.current && <span className="badge"> 현재 기기</span>}
                <span className="muted small">
                  {' '}
                  · {s.ipPrefix ?? 'IP 미상'} · 마지막 사용 {formatDateTime(s.lastRotatedAt ?? s.createdAt)}
                </span>
              </span>
              {!s.current && (
                <button type="button" disabled={revoke.isPending} onClick={() => revoke.mutate(s.id)}>
                  로그아웃
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

const ACTIVE = new Set(['PENDING', 'PROCESSING'])
const FAILURE_TEXT: Record<string, string> = {
  EXPORT_TOO_LARGE: '데이터가 내보내기 크기 한도를 넘었습니다. 보관 항목을 빼고 다시 시도해 보세요.',
  EXPORT_FAILED: '내보내기에 실패했습니다. 잠시 후 다시 시도해 주세요.',
}

function ExportSection() {
  const queryClient = useQueryClient()
  const [includeArchived, setIncludeArchived] = useState(false)
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const recent = useQuery({ queryKey: ['exports'], queryFn: recentExports })
  const latest: ExportJob | undefined = recent.data?.[0]
  const active = latest && ACTIVE.has(latest.status) ? latest : undefined

  // 진행 중인 job만 2초마다 확인한다. 새로고침해도 목록 조회로 이어서 보여 준다.
  const polled = useQuery({
    queryKey: ['exports', active?.jobId],
    queryFn: () => getExport(active!.jobId),
    enabled: Boolean(active),
    refetchInterval: 2000,
  })
  useEffect(() => {
    if (polled.data && !ACTIVE.has(polled.data.status)) void queryClient.invalidateQueries({ queryKey: ['exports'] })
  }, [polled.data, queryClient])

  const create = useMutation({
    mutationFn: (token: string) => createExport(includeArchived, token),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['exports'] }),
    onError: setError,
  })
  const info = error ? toApiError(error) : null
  const view = polled.data ?? latest

  // 다운로드 링크는 조회할 때마다 새 일회성 토큰으로 바뀐다. 눌렀을 때 새로 받아 열고, 목록은 다시 불러온다.
  async function download(job: ExportJob) {
    const fresh = await getExport(job.jobId)
    if (fresh.downloadUrl) window.location.assign(downloadPath(fresh.downloadUrl))
    window.setTimeout(() => void queryClient.invalidateQueries({ queryKey: ['exports'] }), 1500)
  }

  return (
    <section aria-labelledby="export-heading">
      <h2 id="export-heading">데이터 내보내기</h2>
      <p className="muted">모든 지식을 Markdown과 JSON이 담긴 ZIP으로 받습니다. 다운로드 링크는 한 번만 쓸 수 있습니다.</p>
      <div className="row">
        <label className="check">
          <input type="checkbox" checked={includeArchived} onChange={(e) => setIncludeArchived(e.target.checked)} />
          보관한 항목 포함
        </label>
        <button type="button" disabled={Boolean(active) || create.isPending} onClick={() => { setError(null); setAsking(true) }}>
          내보내기 만들기
        </button>
      </div>
      {info && (
        <p role="alert" className="error-text">
          {info.code === 'EXPORT_IN_PROGRESS'
            ? '이미 진행 중인 내보내기가 있습니다.'
            : info.status === 429
              ? '내보내기는 잠시 간격을 두고 다시 만들 수 있습니다.'
              : info.message}
        </p>
      )}
      {view && (
        <div role="status" aria-live="polite" className="node-card" style={{ marginTop: 'var(--space-3)' }}>
          {ACTIVE.has(view.status) && <p>내보내기를 만드는 중입니다...</p>}
          {view.status === 'COMPLETED' && (
            <p>
              내보내기가 준비되었습니다.{' '}
              <button type="button" onClick={() => void download(view)}>
                ZIP 다운로드
              </button>
              {view.expiresAt && <span className="muted small"> · {formatDateTime(view.expiresAt)}까지</span>}
            </p>
          )}
          {view.status === 'FAILED' && <p className="error-text">{FAILURE_TEXT[view.failureReason ?? ''] ?? FAILURE_TEXT.EXPORT_FAILED}</p>}
          {view.status === 'EXPIRED' && <p className="muted">이전 내보내기는 만료되었습니다. 새로 만들어 주세요.</p>}
        </div>
      )}
      {asking && (
        <ReauthDialog
          purpose="EXPORT_CREATE"
          title="내보내기 확인"
          description="내보내기를 만들려면 비밀번호를 다시 입력해 주세요."
          confirmLabel="내보내기 시작"
          onCancel={() => setAsking(false)}
          onToken={(token) => {
            setAsking(false)
            create.mutate(token)
          }}
        />
      )}
    </section>
  )
}

function DangerSection() {
  const email = useAuthStore((state) => state.user?.email ?? '')
  const setAnonymous = useAuthStore((state) => state.setAnonymous)
  const navigate = useNavigate()
  const [confirmation, setConfirmation] = useState('')
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(token: string) {
    setAsking(false)
    try {
      await requestDeletion(confirmation, token)
      // 서버가 모든 세션을 끊었다. 같은 계정으로 다시 로그인하면 7일 안에 취소할 수 있다.
      setAnonymous()
      navigate('/login', { replace: true })
    } catch (err) {
      setError(toApiError(err).message)
    }
  }

  return (
    <section aria-labelledby="danger-heading" className="conflict">
      <h2 id="danger-heading">계정 삭제</h2>
      <p>
        요청하면 모든 기기에서 로그아웃되고 7일 뒤 모든 데이터가 영구 삭제됩니다. 그 전에 다시 로그인하면 삭제를 취소할 수 있습니다.
      </p>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          setAsking(true)
        }}
      >
        <label>
          확인을 위해 이메일({email})을 입력하세요
          <input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} autoComplete="off" />
        </label>
        {error && (
          <p role="alert" className="error-text">
            {error}
          </p>
        )}
        <button type="submit" disabled={confirmation !== email}>
          계정 삭제 요청
        </button>
      </form>
      {asking && (
        <ReauthDialog
          purpose="ACCOUNT_DELETE_REQUEST"
          title="계정 삭제 확인"
          description="계정 삭제를 요청하려면 비밀번호를 다시 입력해 주세요."
          confirmLabel="삭제 요청"
          onCancel={() => setAsking(false)}
          onToken={(token) => void submit(token)}
        />
      )}
    </section>
  )
}

export function SettingsPage() {
  return (
    <div className="settings">
      <h1>설정</h1>
      <PasswordSection />
      <SessionsSection />
      <ExportSection />
      <DangerSection />
    </div>
  )
}
