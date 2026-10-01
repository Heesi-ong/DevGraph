import { apiClient } from '../../shared/api/client'

export interface ExportJob {
  jobId: string
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED'
  downloadUrl: string | null
  expiresAt: string | null
  failureReason: string | null
}

export async function createExport(includeArchived: boolean, reauthToken: string) {
  const { data } = await apiClient.post<{ jobId: string }>('/exports', { includeArchived, format: 'ZIP' }, {
    headers: { 'X-Reauth-Token': reauthToken },
  })
  return data.jobId
}

export async function getExport(jobId: string) {
  const { data } = await apiClient.get<ExportJob>(`/exports/${jobId}`)
  return data
}

export async function recentExports() {
  const { data } = await apiClient.get<ExportJob[]>('/exports')
  return data
}

// 서버가 주는 절대 URL은 설정된 공개 주소 기준이다. 같은 origin의 경로만 써서 개발 proxy/운영 reverse proxy 모두에서 열리게 한다.
export function downloadPath(url: string) {
  const parsed = new URL(url, window.location.origin)
  return parsed.pathname + parsed.search
}
