import type { NodeStatus, PageResponse } from '../knowledge-node/types'
import type { NodeRelations } from '../relation/types'
import type { Tag } from '../tag/types'

export type { PageResponse }

export type ResolutionStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED' | 'WONT_FIX'

// 서버(ResolutionStatus.next)와 같은 전이 규칙. 화면이 허용되지 않는 선택지를 미리 숨기는 데 쓰고, 최종 판단은 서버다.
export const NEXT_RESOLUTION: Record<ResolutionStatus, ResolutionStatus[]> = {
  OPEN: ['INVESTIGATING', 'RESOLVED', 'WONT_FIX'],
  INVESTIGATING: ['OPEN', 'RESOLVED', 'WONT_FIX'],
  RESOLVED: ['OPEN'],
  WONT_FIX: ['OPEN'],
}

export const RESOLUTION_LABEL: Record<ResolutionStatus, string> = {
  OPEN: '열림',
  INVESTIGATING: '조사 중',
  RESOLVED: '해결됨',
  WONT_FIX: '해결 안 함',
}

export interface ErrorData {
  errorMessage: string
  environment: string | null
  reproductionStepsMd: string | null
  causeHypothesisMd: string | null
  resolutionStatus: ResolutionStatus
  occurredAt: string
  resolvedAt: string | null
}

export interface ErrorDetail {
  id: string
  type: 'ERROR'
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  error: ErrorData
  relations: NodeRelations
  // 저장은 됐지만 알아야 할 것. 예: NO_SOLUTION_LINKED(§9.7).
  warnings: string[]
}

export interface ErrorSummary {
  id: string
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  updatedAt: string
  messagePreview: string
  resolutionStatus: ResolutionStatus
  occurredAt: string
  resolvedAt: string | null
  solutionCount: number
  projectCount: number
}

export interface SolutionData {
  approachMd: string
  stepsMd: string | null
  verificationMd: string | null
  tradeoffsMd: string | null
  resolvedAt: string | null
}

export interface SolutionDetail {
  id: string
  type: 'SOLUTION'
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  solution: SolutionData
  relations: NodeRelations
}

export interface SolutionSummary {
  id: string
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  updatedAt: string
  approachPreview: string
  resolvedAt: string | null
  errorCount: number
  snippetCount: number
  projectCount: number
}

export interface ErrorFilters {
  resolution: ResolutionStatus[]
  projectId?: string
  status?: NodeStatus
}

export interface SolutionFilters {
  errorId?: string
  projectId?: string
  status?: NodeStatus
}
