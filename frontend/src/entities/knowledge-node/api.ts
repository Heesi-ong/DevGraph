import { apiClient } from '../../shared/api/client'
import type { NodeDetail, NodeFilters, NodeSummary, NodeType, PageResponse } from './types'

export async function listNodes(filters: NodeFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<NodeSummary>>('/nodes', {
    params: { ...filters, cursor, size },
  })
  return data
}

export async function getNode(id: string) {
  const { data } = await apiClient.get<NodeDetail>(`/nodes/${id}`)
  return data
}

export interface NodeInput {
  title: string
  summary: string
  bodyMd: string
  tagIds: string[]
}

export async function createNode(input: NodeInput & { type: NodeType }) {
  const { data } = await apiClient.post<NodeDetail>('/nodes', input)
  return data
}

export async function updateNode(id: string, version: number, input: NodeInput) {
  const { data } = await apiClient.patch<NodeDetail>(`/nodes/${id}`, { version, ...input })
  return data
}

// 상태 전이는 각각 명시적 endpoint를 가진다(설계서 §11.3).
export type NodeTransition = 'archive' | 'archive/restore' | 'trash' | 'trash/restore'

export async function transitionNode(id: string, version: number, transition: NodeTransition) {
  const { data } = await apiClient.post<NodeSummary>(`/nodes/${id}/${transition}`, { version })
  return data
}

export async function setFavorite(id: string, favorite: boolean) {
  if (favorite) {
    await apiClient.put(`/nodes/${id}/favorite`)
  } else {
    await apiClient.delete(`/nodes/${id}/favorite`)
  }
}
