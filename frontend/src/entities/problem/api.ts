import { apiClient } from '../../shared/api/client'
import type {
  ErrorDetail,
  ErrorFilters,
  ErrorSummary,
  PageResponse,
  ResolutionStatus,
  SolutionDetail,
  SolutionFilters,
  SolutionSummary,
} from './types'

export async function listErrors(filters: ErrorFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<ErrorSummary>>('/errors', {
    params: {
      // axios 기본 직렬화(`resolution[]=`)를 피하려고 콤마로 합쳐 보낸다.
      resolution: filters.resolution.length ? filters.resolution.join(',') : undefined,
      projectId: filters.projectId,
      status: filters.status,
      cursor,
      size,
    },
  })
  return data
}

export async function getError(id: string) {
  const { data } = await apiClient.get<ErrorDetail>(`/errors/${id}`)
  return data
}

export interface ErrorInput {
  title: string
  summary: string
  errorMessage: string
  environment: string
  reproductionStepsMd: string
  causeHypothesisMd: string
  occurredAt?: string
  tagIds: string[]
}

export async function createError(input: ErrorInput) {
  const { data } = await apiClient.post<ErrorDetail>('/errors', input)
  return data
}

export async function updateError(id: string, version: number, input: ErrorInput) {
  const { data } = await apiClient.patch<ErrorDetail>(`/errors/${id}`, { version, ...input })
  return data
}

export async function changeErrorStatus(id: string, version: number, status: ResolutionStatus, resolvedAt?: string) {
  const { data } = await apiClient.patch<ErrorDetail>(`/errors/${id}/status`, { version, status, resolvedAt })
  return data
}

export async function listSolutions(filters: SolutionFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<SolutionSummary>>('/solutions', {
    params: { errorId: filters.errorId, projectId: filters.projectId, status: filters.status, cursor, size },
  })
  return data
}

export async function getSolution(id: string) {
  const { data } = await apiClient.get<SolutionDetail>(`/solutions/${id}`)
  return data
}

export interface SolutionInput {
  title: string
  summary: string
  approachMd: string
  stepsMd: string
  verificationMd: string
  tradeoffsMd: string
  tagIds: string[]
}

// errorNodeId를 주면 Solution과 Error --SOLVED_BY--> Solution을 서버가 한 트랜잭션으로 만든다.
export async function createSolution(input: SolutionInput & { errorNodeId?: string }) {
  const { data } = await apiClient.post<SolutionDetail>('/solutions', input)
  return data
}

export async function updateSolution(id: string, version: number, input: SolutionInput) {
  const { data } = await apiClient.patch<SolutionDetail>(`/solutions/${id}`, { version, ...input })
  return data
}
