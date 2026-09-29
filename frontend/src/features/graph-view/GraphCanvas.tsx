import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useMemo } from 'react'
import type { GraphResponse } from '../../entities/graph/types'
import { layoutGraph, NODE_HEIGHT, NODE_WIDTH } from './layout'

interface Props {
  graph: GraphResponse
  focusId?: string
  onSelect: (nodeId: string | null) => void
}

// 서버 응답을 React Flow 모델로 바꾸는 adapter(§16.3). 그래프가 바뀌면 부모가 key로 다시 마운트해
// React Flow가 선택 상태를 내부에서 관리하게 둔다(마우스 클릭과 키보드 선택이 같은 경로로 onSelectionChange에 온다).
export function GraphCanvas({ graph, focusId, onSelect }: Props) {
  const { nodes, edges } = useMemo(() => {
    const positions = layoutGraph(graph.nodes, graph.edges)
    const nodes: Node[] = graph.nodes.map((n) => ({
      id: n.id,
      position: positions.get(n.id) ?? { x: 0, y: 0 },
      data: { label: `${n.title}` },
      className: `graph-node graph-node--${n.type}${n.id === focusId ? ' graph-node--focus' : ''}${
        n.status === 'ARCHIVED' ? ' graph-node--archived' : ''
      }`,
      style: { width: NODE_WIDTH, height: NODE_HEIGHT },
      ariaLabel: `${n.type} ${n.title}${n.status === 'ARCHIVED' ? ' (보관됨)' : ''}`,
      draggable: false,
    }))
    const edges: Edge[] = graph.edges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.label,
      markerEnd: e.directed ? { type: MarkerType.ArrowClosed } : undefined,
      ariaLabel: e.label,
    }))
    return { nodes, edges }
  }, [graph, focusId])

  return (
    <div className="graph-canvas" data-testid="graph-canvas">
      <ReactFlow
        defaultNodes={nodes}
        defaultEdges={edges}
        fitView
        minZoom={0.2}
        nodesConnectable={false}
        elementsSelectable
        nodesFocusable
        edgesFocusable
        onSelectionChange={({ nodes: selected }) => onSelect(selected[0]?.id ?? null)}
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls showInteractive={false} />
      </ReactFlow>
    </div>
  )
}
