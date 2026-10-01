import type { NodeRelations } from '../relation/types'
import type { Tag } from '../tag/types'
import type { NodeStatus, PageResponse } from '../knowledge-node/types'

export type { PageResponse }

export interface SnippetMeta {
  language: string
  framework: string | null
  currentVersionNo: number
  useCount: number
  lastUsedAt: string | null
  secretScanStatus: 'CLEAN' | 'CONFIRMED_WITH_FINDINGS'
}

export interface VersionContent {
  versionNo: number
  code: string
  changeSummary: string | null
  createdAt: string
}

export interface SnippetDetail {
  id: string
  type: 'SNIPPET'
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  snippet: SnippetMeta
  currentVersion: VersionContent
  relations: NodeRelations
}

// 목록은 코드 원문을 싣지 않는다.
export interface SnippetSummary {
  id: string
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  language: string
  framework: string | null
  currentVersionNo: number
  useCount: number
  lastUsedAt: string | null
}

export interface VersionSummary {
  versionNo: number
  changeSummary: string | null
  createdAt: string
  codeLength: number
}

export interface SnippetFilters {
  language?: string
  framework?: string
  tagId?: string
  status?: NodeStatus
  favorite?: boolean
}

// §17.5: 일반 의심은 CONFIRMED, private key는 CONFIRMED_HIGH_RISK(재확인)여야 저장된다.
export type SecretConfirmation = 'CONFIRMED' | 'CONFIRMED_HIGH_RISK'

// 설계서 §14.4 GET /snippets/{id}/diff — 줄 단위 diff(hunk 3줄 컨텍스트).
export interface DiffLine {
  type: 'CONTEXT' | 'ADD' | 'DELETE'
  oldNo: number
  newNo: number
  text: string
  noEol: boolean
}
export interface DiffHunk {
  oldStart: number
  oldLines: number
  newStart: number
  newLines: number
  lines: DiffLine[]
}
export interface SnippetDiff {
  from: number
  to: number
  added: number
  deleted: number
  hunks: DiffHunk[]
}
