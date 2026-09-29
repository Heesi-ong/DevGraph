import type { Tag } from '../tag/types'

export type NodeType = 'CONCEPT' | 'NOTE'
export type NodeStatus = 'ACTIVE' | 'ARCHIVED' | 'TRASHED'

export interface NodeSummary {
  id: string
  type: NodeType
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
}

export interface NodeDetail extends NodeSummary {
  bodyMd: string | null
}

// 설계서 §14.7 cursor 목록 공통 구조.
export interface PageResponse<T> {
  items: T[]
  cursor: string | null
  hasMore: boolean
}

export interface NodeFilters {
  type?: NodeType
  tagId?: string
  status?: NodeStatus
  favorite?: boolean
}
