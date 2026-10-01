import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { cancelDeletion, changePassword } from '../features/account/api'
import { logout, reloadSession } from '../features/auth/api'
import { useAuthStore } from '../features/auth/authStore'
import { toApiError } from '../shared/api/errors'

// 설계서 §17.2.3 제한 세션: 서버가 허용한 작업(비밀번호 변경, 탈퇴 취소, 로그아웃)만 보여 준다. 다른 화면으로 가지 못하게 한다.
export function RestrictedAccountPage() {
  const restriction = useAuthStore((state) => state.restriction)
  const setAnonymous = useAuthStore((state) => state.setAnonymous)
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await reloadSession()
    } catch (err) {
      const info = toApiError(err)
      setError(info.status === 401 ? '비밀번호가 올바르지 않습니다.' : info.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleLogout() {
    await logout()
    setAnonymous()
    navigate('/login', { replace: true })
  }

  function submitPassword(event: FormEvent) {
    event.preventDefault()
    void run(() => changePassword(current, next, true))
  }

  return (
    <main className="restricted">
      {restriction === 'MUST_CHANGE_PASSWORD' ? (
        <>
          <h1>비밀번호를 변경해 주세요</h1>
          <p className="muted">관리자가 비밀번호를 초기화했습니다. 새 비밀번호를 정하면 계속 사용할 수 있습니다.</p>
          <form className="form" onSubmit={submitPassword}>
            <label>
              현재(임시) 비밀번호
              <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
            </label>
            <label>
              새 비밀번호(8자 이상)
              <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={8} required />
            </label>
            {error && (
              <p role="alert" className="error-text">
                {error}
              </p>
            )}
            <button type="submit" disabled={busy}>
              비밀번호 변경
            </button>
          </form>
        </>
      ) : (
        <>
          <h1>계정 삭제가 예약되어 있습니다</h1>
          <p>예약된 시점에 모든 데이터가 영구 삭제됩니다. 지금 취소하면 계정을 그대로 사용할 수 있습니다.</p>
          {error && (
            <p role="alert" className="error-text">
              {error}
            </p>
          )}
          <button type="button" disabled={busy} onClick={() => void run(cancelDeletion)}>
            삭제 취소
          </button>
        </>
      )}
      <p>
        <button type="button" onClick={() => void handleLogout()}>
          로그아웃
        </button>
      </p>
    </main>
  )
}
