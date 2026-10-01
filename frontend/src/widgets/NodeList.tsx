import { Link } from 'react-router-dom'
import type { AnyNodeType, NodeSummary } from '../entities/knowledge-node/types'
import { nodePath } from '../entities/relation/paths'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'

const TYPE_LABEL: Record<AnyNodeType, string> = {
  CONCEPT: 'Concept', NOTE: 'Note', SNIPPET: 'Snippet', ERROR: 'Error', SOLUTION: 'Solution', RESOURCE: 'Resource', PROJECT: 'Project',
}

export function NodeList({ nodes }: { nodes: NodeSummary[] }) {
  return (
    <ul className="node-list">
      {nodes.map((node) => (
        <li key={node.id} className="node-card">
          <div className="row">
            <span className="badge">{TYPE_LABEL[node.type]}</span>
            <Link to={nodePath(node.type, node.id)} className="node-title">
              {node.title}
            </Link>
            <FavoriteButton nodeId={node.id} favorite={node.favorite} />
          </div>
          {node.summary && <p className="muted">{node.summary}</p>}
          {node.tags.length > 0 && (
            <ul className="chips" aria-label="태그">
              {node.tags.map((tag) => (
                <li key={tag.id} className="chip">
                  {tag.name}
                </li>
              ))}
            </ul>
          )}
          <p className="muted small">{new Date(node.updatedAt).toLocaleString('ko-KR')} 수정</p>
        </li>
      ))}
    </ul>
  )
}
