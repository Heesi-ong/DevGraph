import { apiClient } from '../../shared/api/client'
import type { GraphResponse } from '../graph/types'
import type { PageResponse, ProjectDetail, ProjectFilters, ProjectStatus, ProjectSummary } from './types'

export async function listProjects(filters: ProjectFilters, cursor?: string, size = 20) {
  const { data } = await apiClient.get<PageResponse<ProjectSummary>>('/projects', {
    params: {
      projectStatus: filters.projectStatus.length ? filters.projectStatus.join(',') : undefined,
      status: filters.status,
      cursor,
      size,
    },
  })
  return data
}

export async function getProject(id: string) {
  const { data } = await apiClient.get<ProjectDetail>(`/projects/${id}`)
  return data
}

export interface ProjectInput {
  title: string
  summary: string
  description: string
  projectStatus: ProjectStatus
  repositoryUrl: string
  startedOn?: string
  endedOn?: string
  clearStartedOn?: boolean
  clearEndedOn?: boolean
  tagIds: string[]
}

export async function createProject(input: ProjectInput) {
  const { data } = await apiClient.post<ProjectDetail>('/projects', input)
  return data
}

export async function updateProject(id: string, version: number, input: ProjectInput) {
  const { data } = await apiClient.patch<ProjectDetail>(`/projects/${id}`, { version, ...input })
  return data
}

export async function getProjectGraph(id: string) {
  const { data } = await apiClient.get<GraphResponse>(`/projects/${id}/graph`)
  return data
}
