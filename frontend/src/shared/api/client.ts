import axios from 'axios'

// 설계서 §16.2: Access Token은 memory에 유지하고 refresh는 HttpOnly cookie로 처리한다.
// localStorage에 토큰을 저장하지 않는다.
let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export const apiClient = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
})

apiClient.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

// TODO(Phase 1, §16.2): 401 응답 시 /auth/refresh를 single-flight로 호출하는 인터셉터를
// 여기에 추가한다 — 동시 다발 401에서 refresh token이 중복 회전되는 문제를 막기 위함이다.
