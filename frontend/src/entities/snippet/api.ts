import { apiClient } from '../../shared/api/client'
import type {
  PageResponse,
  SecretConfirmation,
  SnippetDetail,
  SnippetFilters,
  SnippetSummary,
  SnippetDiff,
  VersionContent,
  VersionSummary,
} from './types'

export async function listSnippets(filters: SnippetFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<SnippetSummary>>('/snippets', {
    params: { ...filters, cursor, size },
  })
  return data
}

export async function getSnippet(id: string) {
  const { data } = await apiClient.get<SnippetDetail>(`/snippets/${id}`)
  return data
}

export interface SnippetInput {
  title: string
  summary: string
  language: string
  framework: string
  code: string
  changeSummary?: string
  tagIds: string[]
  secretConfirmation?: SecretConfirmation
}

export async function createSnippet(input: SnippetInput) {
  const { data } = await apiClient.post<SnippetDetail>('/snippets', input)
  return data
}

export async function updateSnippet(id: string, version: number, input: SnippetInput) {
  const { data } = await apiClient.patch<SnippetDetail>(`/snippets/${id}`, { version, ...input })
  return data
}

export async function listVersions(id: string, cursor?: string) {
  const { data } = await apiClient.get<PageResponse<VersionSummary>>(`/snippets/${id}/versions`, {
    params: { cursor, size: 20 },
  })
  return data
}

export async function getVersion(id: string, versionNo: number) {
  const { data } = await apiClient.get<VersionContent>(`/snippets/${id}/versions/${versionNo}`)
  return data
}

// 복사 통계(SNP-08). 실패해도 복사 자체를 실패시키면 안 되므로 호출하는 쪽이 결과를 무시한다(§14.4).
export async function recordCopy(id: string) {
  await apiClient.post(`/snippets/${id}/usage`, { action: 'COPY' })
}

export async function getDiff(id: string, from: number, to: number) {
  const { data } = await apiClient.get<SnippetDiff>(`/snippets/${id}/diff`, { params: { from, to } })
  return data
}
