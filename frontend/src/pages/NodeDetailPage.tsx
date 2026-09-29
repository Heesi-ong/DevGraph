import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { getNode } from '../entities/knowledge-node/api'
import { nodeKeys } from '../entities/knowledge-node/queryKeys'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { StatusActions } from '../features/node-status/StatusActions'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { MarkdownView } from '../widgets/MarkdownView'

export function NodeDetailPage() {
  const { id = '' } = useParams()
  const { data: node, isLoading, error, refetch } = useQuery({
    queryKey: nodeKeys.detail(id),
    queryFn: () => getNode(id),
  })

  if (isLoading) return <LoadingState />
  if (error || !node) return <ErrorState error={error} onRetry={() => refetch()} />

  return (
    <article>
      <div className="row">
        <span className="badge">{node.type}</span>
        <h1>{node.title}</h1>
        <FavoriteButton nodeId={node.id} favorite={node.favorite} />
      </div>
      {node.status !== 'ACTIVE' && <p className="muted">상태: {node.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      {node.summary && <p className="muted">{node.summary}</p>}
      {node.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {node.tags.map((tag) => (
            <li key={tag.id} className="chip">
              <Link to={`/library?tagId=${tag.id}`}>{tag.name}</Link>
            </li>
          ))}
        </ul>
      )}
      <MarkdownView source={node.bodyMd || '_본문이 없습니다._'} />
      <div className="row">
        {node.status !== 'TRASHED' && <Link to={`/nodes/${node.id}/edit`}>편집</Link>}
        <StatusActions node={node} />
      </div>
      <RelatedNodes nodeId={node.id} nodeType={node.type} relations={node.relations} editable={node.status !== 'TRASHED'} />
      <p className="muted small">{new Date(node.updatedAt).toLocaleString('ko-KR')} 수정</p>
    </article>
  )
}
