import type { GraphFilters } from './types'

export const graphKeys = {
  all: ['graph'] as const,
  view: (filters: GraphFilters) => ['graph', 'view', filters] as const,
}
