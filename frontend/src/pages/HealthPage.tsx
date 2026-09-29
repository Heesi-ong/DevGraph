import { useQuery } from '@tanstack/react-query'
import axios from 'axios'
import { useNavigate } from 'react-router-dom'
import { logout } from '../features/auth/api'
import { useAuthStore } from '../features/auth/authStore'

interface HealthResponse {
  status: string
}

// Phase 0 완료 조건 확인용: frontend가 backend health를 호출한다(설계서 19절 Phase 0).
// 로그인 후 첫 화면(Phase 1) — 실제 Dashboard는 Phase 2에서 만든다.
export function HealthPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['backend-health'],
    queryFn: async () => {
      const response = await axios.get<HealthResponse>('/actuator/health')
      return response.data
    },
  })
  const user = useAuthStore((state) => state.user)
  const workspace = useAuthStore((state) => state.workspace)
  const setAnonymous = useAuthStore((state) => state.setAnonymous)
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    setAnonymous()
    navigate('/login', { replace: true })
  }

  return (
    <main style={{ padding: 'var(--space-4)' }}>
      <h1>DevGraph</h1>
      <p>Backend health: {isLoading ? '확인 중...' : isError ? '연결 실패' : data?.status}</p>
      {user && (
        <p>
          {user.displayName}님, {workspace?.name ?? '개인 Workspace'}에 로그인했습니다.
        </p>
      )}
      <button type="button" onClick={handleLogout}>
        로그아웃
      </button>
    </main>
  )
}
