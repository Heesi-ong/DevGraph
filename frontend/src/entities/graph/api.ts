import { apiClient } from '../../shared/api/client'
import type { GraphFilters, GraphResponse } from './types'

// axios 기본 직렬화는 배열을 `nodeTypes[]=`로 보내 서버가 못 읽는다. 콤마로 합쳐 보낸다(Spring이 List로 분해).
function params(filters: GraphFilters) {
  return {
    nodeTypes: filters.nodeTypes.length ? filters.nodeTypes.join(',') : undefined,
    relationTypes: filters.relationTypes.length ? filters.relationTypes.join(',') : undefined,
    archived: filters.archived || undefined,
  }
}

export async function getFocusGraph(filters: GraphFilters & { focus: string }) {
  const { data } = await apiClient.get<GraphResponse>(`/graph/focus/${filters.focus}`, {
    params: { ...params(filters), depth: filters.depth },
  })
  return data
}

// Workspace Graph는 최근 수정 순 첫 페이지만 쓴다. 페이지를 넘나드는 edge가 응답에 없어서 여러 페이지를 이어 붙이지 않는다.
export async function getWorkspaceGraph(filters: GraphFilters) {
  const { data } = await apiClient.get<GraphResponse>('/graph/workspace', {
    params: { ...params(filters), size: 100 },
  })
  return data
}
