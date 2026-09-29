import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

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

// §16.2 필수 규칙: 동시 다발 401에서 refresh가 중복 호출되면 refresh token이 여러 번 회전하면서
// 재사용 감지(§17.2)로 family 전체가 죽는다 — 진행 중인 refresh 하나를 모든 401이 공유해야 한다.
let refreshInFlight: Promise<string> | null = null

interface RetryableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean
}

// 토큰 발급/회전 자체를 담당하는 엔드포인트는 재시도 루프에서 제외한다 — 그렇지 않으면
// refresh 실패가 refresh를 다시 refresh로 재시도하는 무한 루프가 될 수 있다.
const NON_RETRYABLE_PATHS = ['/auth/login', '/auth/signup', '/auth/refresh', '/auth/logout']

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as RetryableConfig | undefined
    const status = error.response?.status
    const isNonRetryable = config?.url ? NON_RETRYABLE_PATHS.includes(config.url) : true

    if (status !== 401 || !config || config._retried || isNonRetryable) {
      return Promise.reject(error)
    }

    config._retried = true
    // 이 요청이 나간 뒤 다른 요청의 refresh가 이미 끝났다면 새 토큰으로 재시도만 한다.
    // 다시 refresh하면 이미 회전된 쿠키로 요청해 재사용 감지(§17.2)가 family를 폐기한다.
    if (accessToken && config.headers.Authorization !== `Bearer ${accessToken}`) {
      config.headers.Authorization = `Bearer ${accessToken}`
      return apiClient.request(config)
    }
    if (!refreshInFlight) {
      // 동적 import: features -> shared 역방향 의존을 피하려고 호출 시점에만 불러온다.
      refreshInFlight = import('../../features/auth/api')
        .then((mod) => mod.refresh())
        .finally(() => {
          refreshInFlight = null
        })
    }

    try {
      const newToken = await refreshInFlight
      config.headers.Authorization = `Bearer ${newToken}`
      return apiClient.request(config)
    } catch (refreshError) {
      return Promise.reject(refreshError)
    }
  },
)
