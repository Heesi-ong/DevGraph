import type { AnyNodeType, NodeStatus } from '../knowledge-node/types'

export interface RelationType {
  id: string
  key: string
  forwardLabel: string
  inverseLabel: string
  description: string | null
  directionality: 'directed' | 'symmetric'
  allowedSourceTypes: AnyNodeType[]
  allowedTargetTypes: AnyNodeType[]
}

// Node 상세의 관계 한 줄. label은 이 Node 기준(forward 또는 inverse)으로 이미 골라져 있다.
// 대칭 관계는 저장 방향과 무관하게 항상 outgoing에 온다.
export interface RelationLink {
  id: string
  relationTypeId: string
  type: string
  label: string
  nodeId: string
  nodeTitle: string
  nodeType: AnyNodeType
  nodeStatus: NodeStatus
  note: string | null
}

export interface NodeRelations {
  outgoing: RelationLink[]
  incoming: RelationLink[]
  truncated: boolean
}

export interface RelationView {
  id: string
  sourceNodeId: string
  targetNodeId: string
  relationTypeId: string
  type: string
  note: string | null
}

export interface TargetCandidate {
  id: string
  title: string
  type: AnyNodeType
  status: NodeStatus
}

export type Side = 'OUTGOING' | 'INCOMING'
