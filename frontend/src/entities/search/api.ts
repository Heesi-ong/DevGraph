import { apiClient } from '../../shared/api/client'
import type { SearchFilters, SearchPage } from './types'

export async function search(filters: SearchFilters, cursor?: string) {
  const { data } = await apiClient.get<SearchPage>('/search', {
    params: {
      q: filters.q,
      // axios는 배열을 `types[]=`로 직렬화하므로 콤마로 합쳐 보낸다(Spring이 List로 분해).
      types: filters.types.length ? filters.types.join(',') : undefined,
      language: filters.language,
      framework: filters.framework,
      archived: filters.archived || undefined,
      cursor,
      size: 20,
    },
  })
  return data
}
