import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuthStore } from '../features/auth/authStore'
import { RestrictedAccountPage } from '../pages/RestrictedAccountPage'

// 설계서 §19 Phase 1 "protected route". 세션 만료 시 로그인 화면으로 보낸다(session 만료 UX).
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status)
  const restriction = useAuthStore((state) => state.restriction)

  if (status === 'loading') {
    return <p style={{ padding: 'var(--space-4)' }}>불러오는 중...</p>
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace />
  }
  // §17.2.3: 서버가 거부할 API를 화면이 부르지 않도록, 제한 상태에서는 허용된 화면만 보여 준다.
  if (restriction !== 'NONE') {
    return <RestrictedAccountPage />
  }
  return children
}
