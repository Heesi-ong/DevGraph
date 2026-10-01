import type { SearchFilters } from './types'

export const searchKeys = {
  all: ['search'] as const,
  results: (filters: SearchFilters) => ['search', 'results', filters] as const,
}
