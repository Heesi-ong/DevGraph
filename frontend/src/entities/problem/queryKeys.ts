import type { ErrorFilters, SolutionFilters } from './types'

export const problemKeys = {
  all: ['problems'] as const,
  errors: (filters: ErrorFilters) => ['problems', 'errors', filters] as const,
  error: (id: string) => ['problems', 'error', id] as const,
  solutions: (filters: SolutionFilters) => ['problems', 'solutions', filters] as const,
  solution: (id: string) => ['problems', 'solution', id] as const,
}
