import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { getNode } from '../entities/knowledge-node/api'
import { nodeKeys } from '../entities/knowledge-node/queryKeys'
import { NodeForm } from '../features/edit-node/NodeForm'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'

export function NodeCreatePage() {
  const navigate = useNavigate()
  return (
    <div>
      <h1>새 지식</h1>
      <NodeForm mode="create" onSaved={(n) => navigate(`/nodes/${n.id}`)} onCancel={() => navigate(-1)} />
    </div>
  )
}

export function NodeEditPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { data: node, isLoading, error, refetch } = useQuery({
    queryKey: nodeKeys.detail(id),
    queryFn: () => getNode(id),
  })

  if (isLoading) return <LoadingState />
  if (error || !node) return <ErrorState error={error} onRetry={() => refetch()} />

  return (
    <div>
      <h1>편집</h1>
      <NodeForm mode="edit" initial={node} onSaved={(n) => navigate(`/nodes/${n.id}`)} onCancel={() => navigate(-1)} />
    </div>
  )
}
