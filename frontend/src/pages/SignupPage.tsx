import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { signup, me } from '../features/auth/api'
import { useAuthStore } from '../features/auth/authStore'

export function SignupPage() {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const setAuthenticated = useAuthStore((state) => state.setAuthenticated)
  const navigate = useNavigate()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await signup(email, displayName, password)
      const meResponse = await me()
      setAuthenticated(meResponse.user, meResponse.workspace, meResponse.restriction)
      navigate('/', { replace: true })
    } catch (err) {
      // §14.1: 409는 EMAIL_UNAVAILABLE(중복 이메일 일반화 오류), 그 외는 400 검증 오류.
      setError('가입할 수 없습니다. 입력값을 확인해 주세요.')
      void err
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main style={{ padding: 'var(--space-4)', maxWidth: 360 }}>
      <h1>가입하기</h1>
      <form onSubmit={handleSubmit}>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          표시 이름
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} />
        </label>
        <label>
          비밀번호(8자 이상)
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
          />
        </label>
        {error && <p style={{ color: 'var(--color-danger)' }}>{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? '가입 중...' : '가입하기'}
        </button>
      </form>
      <p>
        이미 계정이 있으신가요? <Link to="/login">로그인</Link>
      </p>
    </main>
  )
}
