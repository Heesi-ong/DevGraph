import { isAxiosError } from 'axios'

export interface ApiErrorInfo {
  status: number | null
  code: string
  message: string
  traceId: string | null
  fieldErrors: { field: string; reason: string }[]
}

// 설계서 §14.1 오류 envelope({code,message,fieldErrors,traceId,timestamp})을 화면에서 쓰기 좋은 형태로 바꾼다.
// 응답 자체가 없는 네트워크 오류도 같은 모양으로 돌려줘 화면이 분기를 늘리지 않게 한다.
export function toApiError(error: unknown): ApiErrorInfo {
  if (isAxiosError(error)) {
    const data = error.response?.data as Partial<ApiErrorInfo> | undefined
    return {
      status: error.response?.status ?? null,
      code: data?.code ?? (error.response ? 'REQUEST_FAILED' : 'NETWORK_ERROR'),
      message: data?.message ?? (error.response ? '요청을 처리하지 못했습니다.' : '서버에 연결할 수 없습니다.'),
      traceId: data?.traceId ?? null,
      fieldErrors: data?.fieldErrors ?? [],
    }
  }
  return {
    status: null,
    code: 'UNKNOWN_ERROR',
    message: error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.',
    traceId: null,
    fieldErrors: [],
  }
}
