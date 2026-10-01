import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getProjectGraph } from '../entities/project/api'
import { projectKeys } from '../entities/project/queryKeys'
import { nodePath } from '../entities/relation/paths'
import { GraphCanvas } from '../features/graph-view/GraphCanvas'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'

// 프로젝트를 중심으로 한 그래프(PROJ-04). React Flow는 이 탭을 열 때만 내려받는다(ProjectDetailPage가 lazy import).
export default function ProjectGraphTab({ projectId }: { projectId: string }) {
  const graph = useQuery({ queryKey: projectKeys.graph(projectId), queryFn: () => getProjectGraph(projectId) })
  const [selected, setSelected] = useState<string | null>(null)
  if (graph.isLoading) return <LoadingState />
  if (graph.error || !graph.data) return <ErrorState error={graph.error} onRetry={() => graph.refetch()} />
  const data = graph.data
  const node = data.nodes.find((n) => n.id === selected)
  if (data.nodes.length <= 1) {
    return <EmptyState title="아직 연결된 항목이 없습니다" description="지식·오류·해결을 이 프로젝트와 연결하면 그래프가 그려져요." />
  }
  return (
    <div>
      {data.truncated && (
        <p role="status" className="truncation">
          항목이 많아 일부만 표시합니다. <Link to={`/graph?focus=${projectId}&depth=1`}>그래프 화면에서 필터로 좁혀 보기</Link>
        </p>
      )}
      <GraphCanvas key={data.nodes.map((n) => n.id).join(',') + data.edges.length} graph={data} focusId={projectId} onSelect={setSelected} />
      {node && (
        <p>
          <span className="badge">{node.type}</span> {node.title} · <Link to={nodePath(node.type, node.id)}>상세 보기</Link>
        </p>
      )}
      <p className="muted small">
        <Link to={`/graph?focus=${projectId}&depth=2`}>전체 그래프 화면에서 열기</Link>
      </p>
    </div>
  )
}
