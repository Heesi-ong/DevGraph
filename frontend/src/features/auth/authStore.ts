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

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

interface AuthState {
  status: AuthStatus
  user: CurrentUser | null
  workspace: CurrentWorkspace | null
  setAuthenticated: (user: CurrentUser, workspace: CurrentWorkspace | null) => void
  setAnonymous: () => void
}

// 설계서 §16.2: 인증 상태는 memory에만 둔다(localStorage에 토큰을 저장하지 않는다).
export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  user: null,
  workspace: null,
  setAuthenticated: (user, workspace) => set({ status: 'authenticated', user, workspace }),
  setAnonymous: () => set({ status: 'anonymous', user: null, workspace: null }),
}))
