import { useEffect, useRef, useState, type FormEvent } from 'react'
import { requestReauth, type ReauthPurpose } from '../account/api'
import { toApiError } from '../../shared/api/errors'

interface Props {
  purpose: ReauthPurpose
  targetId?: string
  title: string
  description: string
  confirmLabel: string
  onToken: (token: string) => void
  onCancel: () => void
}

// 설계서 §17.2.2: 위험 작업 직전 비밀번호를 다시 받아 5분짜리 일회성 토큰으로 바꾼다. 비밀번호는 저장하지 않는다.
// 네이티브 <dialog>의 showModal은 포커스 가둠·Esc 닫기·배경 비활성화를 브라우저가 처리해 준다.
export function ReauthDialog({ purpose, targetId, title, description, confirmLabel, onToken, onCancel }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const token = await requestReauth(password, purpose, targetId)
      setPassword('')
      onToken(token)
    } catch (err) {
      const info = toApiError(err)
      setError(
        info.status === 401 ? '비밀번호가 올바르지 않습니다.' : info.status === 429 ? '시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.' : info.message,
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <dialog ref={ref} className="dialog" aria-labelledby="reauth-title" onCancel={onCancel} onClose={onCancel}>
      <form className="form" onSubmit={submit}>
        <h2 id="reauth-title">{title}</h2>
        <p className="muted">{description}</p>
        <label>
          현재 비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        {error && (
          <p role="alert" className="error-text">
            {error}
          </p>
        )}
        <div className="row">
          <button type="submit" disabled={busy || !password}>
            {busy ? '확인 중...' : confirmLabel}
          </button>
          <button type="button" onClick={onCancel}>
            취소
          </button>
        </div>
      </form>
    </dialog>
  )
}
