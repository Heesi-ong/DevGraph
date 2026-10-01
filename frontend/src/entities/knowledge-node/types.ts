import type { NodeRelations } from '../relation/types'
import type { Tag } from '../tag/types'

export type NodeType = 'CONCEPT' | 'NOTE'
// 서버가 다루는 전체 타입(§11.2). Relation/Graph는 Snippet 등 다른 타입의 Node도 가리킨다.
export type AnyNodeType = 'CONCEPT' | 'NOTE' | 'SNIPPET' | 'ERROR' | 'SOLUTION' | 'RESOURCE' | 'PROJECT'
export type NodeStatus = 'ACTIVE' | 'ARCHIVED' | 'TRASHED'

export interface NodeSummary {
  id: string
  // Library 목록은 Concept·Note·Error·Solution·Resource를 함께 준다.
  type: AnyNodeType
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
  relations: NodeRelations
}

// 설계서 §14.7 cursor 목록 공통 구조.
export interface PageResponse<T> {
  items: T[]
  cursor: string | null
  hasMore: boolean
}

export interface NodeFilters {
  type?: AnyNodeType
  tagId?: string
  status?: NodeStatus
  favorite?: boolean
}
