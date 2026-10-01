import type { AnyNodeType, NodeStatus } from '../knowledge-node/types'

export interface GraphNode {
  id: string
  type: AnyNodeType
  title: string
  status: NodeStatus
  depth: number
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  type: string
  label: string
  directed: boolean
}

export interface ExpansionCandidate {
  nodeId: string
  title: string
  type: AnyNodeType
  viaRelation: string
}

export type TruncationReason = 'NONE' | 'MAX_NODES' | 'MAX_EDGES' | 'MAX_DEPTH' | 'QUERY_ROW_CAP'

export interface GraphResponse {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
  truncationReason: TruncationReason
  appliedFilters: { depth: number; nodeTypes: string[]; relationTypes: string[]; includeArchived: boolean }
  nextExpansionCandidates: ExpansionCandidate[]
  cursor: string | null
  limits: { maxNodes: number; maxEdges: number }
}

// URL search params가 그래프 화면의 유일한 상태다(§16).
export interface GraphFilters {
  focus?: string
  depth: number
  nodeTypes: AnyNodeType[]
  relationTypes: string[]
  archived: boolean
}
