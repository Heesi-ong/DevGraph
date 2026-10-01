import type { NodeStatus, PageResponse } from '../knowledge-node/types'
import type { NodeRelations } from '../relation/types'
import type { Tag } from '../tag/types'

export type { PageResponse }

export type ProjectStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED'

export const PROJECT_STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: '진행 중',
  PAUSED: '일시 중지',
  COMPLETED: '완료',
  ARCHIVED: '종료',
}

export interface ProjectData {
  projectStatus: ProjectStatus
  repositoryUrl: string | null
  startedOn: string | null
  endedOn: string | null
}

export interface ProjectDetail {
  id: string
  type: 'PROJECT'
  title: string
  summary: string | null
  description: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  createdAt: string
  updatedAt: string
  project: ProjectData
  relations: NodeRelations
}

export interface ProjectSummary {
  id: string
  title: string
  summary: string | null
  status: NodeStatus
  version: number
  tags: Tag[]
  favorite: boolean
  updatedAt: string
  projectStatus: ProjectStatus
  repositoryUrl: string | null
  startedOn: string | null
  endedOn: string | null
  problemCount: number
  knowledgeCount: number
  solutionCount: number
}

export interface ProjectFilters {
  projectStatus: ProjectStatus[]
  status?: NodeStatus
}
