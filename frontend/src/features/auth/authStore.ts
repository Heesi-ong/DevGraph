import { create } from 'zustand'

export interface CurrentUser {
  id: string
  email: string
  displayName: string
}

export interface CurrentWorkspace {
  id: string
  name: string
  slug: string
}

// 설계서 §17.2.3: 서버가 JWT restriction claim으로 알려 주는 제한 상태. 제한 세션은 허용 목록 API만 쓸 수 있다.
export type Restriction = 'NONE' | 'MUST_CHANGE_PASSWORD' | 'DELETION_PENDING'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

interface AuthState {
  status: AuthStatus
  user: CurrentUser | null
  workspace: CurrentWorkspace | null
  restriction: Restriction
  setAuthenticated: (user: CurrentUser, workspace: CurrentWorkspace | null, restriction?: Restriction) => void
  setAnonymous: () => void
}

// 설계서 §16.2: 인증 상태는 memory에만 둔다(localStorage에 토큰을 저장하지 않는다).
export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  user: null,
  workspace: null,
  restriction: 'NONE',
  setAuthenticated: (user, workspace, restriction = 'NONE') => set({ status: 'authenticated', user, workspace, restriction }),
  setAnonymous: () => set({ status: 'anonymous', user: null, workspace: null, restriction: 'NONE' }),
}))
