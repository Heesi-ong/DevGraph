import type { ResourceFilters } from './types'

export const resourceKeys = {
  all: ['resources'] as const,
  list: (filters: ResourceFilters) => ['resources', 'list', filters] as const,
  detail: (id: string) => ['resources', 'detail', id] as const,
}
