import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { login, me } from '../features/auth/api'
import { useAuthStore } from '../features/auth/authStore'

export function LoginPage() {
  const [email, setEmail] = useState('')
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
      await login(email, password)
      const meResponse = await me()
      setAuthenticated(meResponse.user, meResponse.workspace)
      navigate('/', { replace: true })
    } catch {
      // §9.1 AUTH-02: 실패 메시지로 계정 존재 여부를 노출하지 않는다.
      setError('이메일 또는 비밀번호가 올바르지 않습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main style={{ padding: 'var(--space-4)', maxWidth: 360 }}>
      <h1>로그인</h1>
      <form onSubmit={handleSubmit}>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p style={{ color: 'var(--color-danger)' }}>{error}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? '로그인 중...' : '로그인'}
        </button>
      </form>
      <p>
        계정이 없으신가요? <Link to="/signup">가입하기</Link>
      </p>
    </main>
  )
}
