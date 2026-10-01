import type { ProjectFilters } from './types'

export const projectKeys = {
  all: ['projects'] as const,
  list: (filters: ProjectFilters) => ['projects', 'list', filters] as const,
  detail: (id: string) => ['projects', 'detail', id] as const,
  graph: (id: string) => ['projects', 'graph', id] as const,
}
