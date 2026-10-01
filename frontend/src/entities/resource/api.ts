import { apiClient } from '../../shared/api/client'
import type { PageResponse, ResourceDetail, ResourceFilters, ResourceKind, ResourceSummary } from './types'

export async function listResources(filters: ResourceFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<ResourceSummary>>('/resources', {
    params: { kind: filters.kinds.length ? filters.kinds.join(',') : undefined, status: filters.status, cursor, size },
  })
  return data
}

export async function getResource(id: string) {
  const { data } = await apiClient.get<ResourceDetail>(`/resources/${id}`)
  return data
}

export interface ResourceInput {
  title: string
  summary: string
  url: string
  kind: ResourceKind
  siteName: string
  tagIds: string[]
}

export async function createResource(input: ResourceInput) {
  const { data } = await apiClient.post<ResourceDetail>('/resources', input)
  return data
}

export async function updateResource(id: string, version: number, input: ResourceInput) {
  const { data } = await apiClient.patch<ResourceDetail>(`/resources/${id}`, { version, ...input })
  return data
}
