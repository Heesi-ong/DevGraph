import type { NodeStatus, PageResponse } from '../knowledge-node/types'
import type { NodeRelations } from '../relation/types'
import type { Tag } from '../tag/types'

export type { PageResponse }

export type ResourceKind = 'WEB' | 'DOC' | 'VIDEO' | 'REPO' | 'BOOK' | 'OTHER'

export const RESOURCE_KINDS: ResourceKind[] = ['WEB', 'DOC', 'VIDEO', 'REPO', 'BOOK', 'OTHER']

export interface ResourceData {
  url: string
  kind: ResourceKind
  siteName: string | null
  lastCheckedAt: string | null
}

export interface DuplicateRef {
  id: string
  title: string
}

export interface ResourceDetail {
  id: string
  type: 'RESOURCE'
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  resource: ResourceData
  // 같은 URL(정규화 기준)의 다른 Resource. 경고만 하고 저장은 막지 않는다.
  duplicates: DuplicateRef[]
  relations: NodeRelations
}

export interface ResourceSummary {
  id: string
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  updatedAt: string
  url: string
  kind: ResourceKind
  siteName: string | null
}

export interface ResourceFilters {
  kinds: ResourceKind[]
  status?: NodeStatus
}
