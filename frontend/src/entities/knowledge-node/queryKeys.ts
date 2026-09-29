import type { NodeFilters } from './types'

// 설계서 §16.2 / §28: query key convention. 필터가 key에 모두 들어가야 캐시가 섞이지 않는다.
// mutation 후에는 전체 무효화 대신 아래 키 범위만 골라 invalidate한다.
export const nodeKeys = {
  all: ['nodes'] as const,
  lists: () => ['nodes', 'list'] as const,
  list: (filters: NodeFilters) => ['nodes', 'list', filters] as const,
  detail: (id: string) => ['nodes', 'detail', id] as const,
  // Dashboard 위젯은 페이지네이션 목록과 캐시 모양이 달라 별도 키 범위를 쓴다(그래도 nodeKeys.all로 함께 무효화된다).
  dashboard: (section: 'recent' | 'favorites') => ['nodes', 'dashboard', section] as const,
}

export const tagKeys = {
  all: ['tags'] as const,
  search: (q: string) => ['tags', 'search', q] as const,
}
