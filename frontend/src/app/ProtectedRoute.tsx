import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuthStore } from '../features/auth/authStore'

// 설계서 §19 Phase 1 "protected route". 세션 만료 시 로그인 화면으로 보낸다(session 만료 UX).
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status)

  if (status === 'loading') {
    return <p style={{ padding: 'var(--space-4)' }}>불러오는 중...</p>
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace />
  }
  return children
}
