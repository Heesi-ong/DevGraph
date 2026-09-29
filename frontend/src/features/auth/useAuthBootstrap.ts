import { useEffect } from 'react'
import { me } from './api'
import { useAuthStore } from './authStore'

// 설계서 §16.1 app shell: 새로고침 시 refresh 쿠키로 session을 복구한다(Phase 1 완료 조건).
// /auth/me가 401을 받으면 shared/api/client의 인터셉터가 refresh 후 자동 재시도한다.
export function useAuthBootstrap() {
  const setAuthenticated = useAuthStore((state) => state.setAuthenticated)
  const setAnonymous = useAuthStore((state) => state.setAnonymous)

  useEffect(() => {
    me()
      .then((response) => setAuthenticated(response.user, response.workspace))
      .catch(() => setAnonymous())
  }, [setAuthenticated, setAnonymous])
}
