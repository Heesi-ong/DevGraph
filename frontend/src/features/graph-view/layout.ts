import dagre from '@dagrejs/dagre'
import type { GraphEdge, GraphNode } from '../../entities/graph/types'

export const NODE_WIDTH = 200
export const NODE_HEIGHT = 48

// 설계서 §9.4 GRPH-05: 검증된 자동 배치 엔진 1개(dagre). 순환이 있어도 dagre가 처리한다.
// Workspace 그래프의 사용자 배치 저장(GRPH-06)은 이번 범위 밖이라 좌표는 매번 계산한다.
export function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]): Map<string, { x: number; y: number }> {
  const graph = new dagre.graphlib.Graph()
  graph.setGraph({ rankdir: 'LR', nodesep: 28, ranksep: 90 })
  graph.setDefaultEdgeLabel(() => ({}))
  nodes.forEach((n) => graph.setNode(n.id, { width: NODE_WIDTH, height: NODE_HEIGHT }))
  edges.forEach((e) => graph.setEdge(e.source, e.target))
  dagre.layout(graph)
  const positions = new Map<string, { x: number; y: number }>()
  nodes.forEach((n) => {
    const p = graph.node(n.id)
    positions.set(n.id, { x: p.x - NODE_WIDTH / 2, y: p.y - NODE_HEIGHT / 2 })
  })
  return positions
}
