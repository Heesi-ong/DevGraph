import { Link } from 'react-router-dom'
import type { SnippetSummary } from '../entities/snippet/types'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'

export function SnippetList({ snippets }: { snippets: SnippetSummary[] }) {
  return (
    <ul className="node-list">
      {snippets.map((snippet) => (
        <li key={snippet.id} className="node-card">
          <div className="row">
            <span className="badge">{snippet.language}</span>
            {snippet.framework && <span className="badge">{snippet.framework}</span>}
            <Link to={`/snippets/${snippet.id}`} className="node-title">
              {snippet.title}
            </Link>
            <FavoriteButton nodeId={snippet.id} favorite={snippet.favorite} />
          </div>
          {snippet.summary && <p className="muted">{snippet.summary}</p>}
          {snippet.tags.length > 0 && (
            <ul className="chips" aria-label="태그">
              {snippet.tags.map((tag) => (
                <li key={tag.id} className="chip">
                  {tag.name}
                </li>
              ))}
            </ul>
          )}
          <p className="muted small">
            v{snippet.currentVersionNo} · 복사 {snippet.useCount}회 · {new Date(snippet.updatedAt).toLocaleString('ko-KR')} 수정
          </p>
        </li>
      ))}
    </ul>
  )
}
