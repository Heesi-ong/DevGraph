import { apiClient, setAccessToken } from '../../shared/api/client'
import { readCookie } from '../../shared/lib/cookies'
import type { CurrentUser, CurrentWorkspace } from './authStore'

interface AuthResponse {
  user: CurrentUser
  accessToken: string
}

interface MeResponse {
  user: CurrentUser
  workspace: CurrentWorkspace | null
}

function csrfHeaders() {
  const token = readCookie('csrf_token')
  return token ? { 'X-CSRF-Token': token } : {}
}

export async function signup(email: string, displayName: string, password: string) {
  const { data } = await apiClient.post<AuthResponse>('/auth/signup', { email, displayName, password })
  setAccessToken(data.accessToken)
  return data
}

export async function login(email: string, password: string) {
  const { data } = await apiClient.post<AuthResponse>('/auth/login', { email, password })
  setAccessToken(data.accessToken)
  return data
}

export async function refresh() {
  const { data } = await apiClient.post<{ accessToken: string }>('/auth/refresh', null, {
    headers: csrfHeaders(),
  })
  setAccessToken(data.accessToken)
  return data.accessToken
}

export async function logout() {
  await apiClient.post('/auth/logout', null, { headers: csrfHeaders() })
  setAccessToken(null)
}

export async function me() {
  const { data } = await apiClient.get<MeResponse>('/auth/me')
  return data
}
