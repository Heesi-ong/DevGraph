import { apiClient } from '../../shared/api/client'
import type { PageResponse } from '../knowledge-node/types'
import type { Tag } from './types'

export async function searchTags(q: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<Tag>>('/tags', { params: { q: q || undefined, size } })
  return data.items
}

export async function createTag(name: string) {
  const { data } = await apiClient.post<Tag>('/tags', { name })
  return data
}
