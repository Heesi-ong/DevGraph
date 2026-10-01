import { apiClient } from '../../shared/api/client'
import type { RelationType, RelationView, Side, TargetCandidate } from './types'

export async function listRelationTypes() {
  const { data } = await apiClient.get<RelationType[]>('/relation-types')
  return data
}

export async function createRelation(input: {
  sourceNodeId: string
  targetNodeId: string
  relationTypeId: string
  note?: string
}) {
  const { data } = await apiClient.post<RelationView>('/relations', input)
  return data
}

export async function updateRelation(id: string, changes: {
  relationTypeId?: string; note?: string; sourceNodeId?: string; targetNodeId?: string
}) {
  const { data } = await apiClient.patch<RelationView>(`/relations/${id}`, changes)
  return data
}

export async function deleteRelation(id: string) {
  await apiClient.delete(`/relations/${id}`)
}

export async function findTargetCandidates(params: { nodeId: string; relationTypeId: string; side: Side; q?: string }) {
  const { data } = await apiClient.get<TargetCandidate[]>('/relations/target-candidates', { params })
  return data
}
