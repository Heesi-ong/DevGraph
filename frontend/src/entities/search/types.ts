import type { AnyNodeType, NodeStatus } from '../knowledge-node/types'

// 설계서 §14.7: highlight는 HTML이 아니라 {text, matched} 구간 배열이다.
export interface Segment {
  text: string
  matched: boolean
}

export type MatchedField = 'title' | 'tag' | 'language' | 'body' | 'code' | 'error'

export interface SearchHit {
  id: string
  type: AnyNodeType
  title: string
  status: NodeStatus
  score: number
  matchedFields: MatchedField[]
  highlight: { title?: Segment[]; summary?: Segment[]; body?: Segment[]; code?: Segment[]; error?: Segment[] }
  language: string | null
  framework: string | null
  favorite: boolean
  updatedAt: string
  // scope=snippetHistory 결과에서만: 일치한 과거 버전 번호(§9.5)
  versionNo?: number | null
}

export interface RecentItem {
  id: string
  type: AnyNodeType
  title: string
  updatedAt: string
}

export interface Fallback {
  unfilteredCount: number | null
  similar: SearchHit[]
  recent: RecentItem[]
}

export interface SearchPage {
  items: SearchHit[]
  cursor: string | null
  hasMore: boolean
  fallback: Fallback | null
}

// URL search params가 검색 화면의 유일한 상태다(§16).
export interface SearchFilters {
  q: string
  types: AnyNodeType[]
  language?: string
  framework?: string
  archived: boolean
  // 과거 버전 코드 검색(`scope=snippetHistory`). 켜면 현재 버전은 대상이 아니다.
  history: boolean
}

export const MIN_QUERY_LENGTH = 2
