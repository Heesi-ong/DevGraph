import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { getSnippet } from '../entities/snippet/api'
import { snippetKeys } from '../entities/snippet/queryKeys'
import { SnippetForm } from '../features/edit-snippet/SnippetForm'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'

export function SnippetCreatePage() {
  const navigate = useNavigate()
  return (
    <div>
      <h1>새 Snippet</h1>
      <SnippetForm mode="create" onSaved={(s) => navigate(`/snippets/${s.id}`)} onCancel={() => navigate(-1)} />
    </div>
  )
}

export function SnippetEditPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { data: snippet, isLoading, error, refetch } = useQuery({
    queryKey: snippetKeys.detail(id),
    queryFn: () => getSnippet(id),
  })

  if (isLoading) return <LoadingState />
  if (error || !snippet) return <ErrorState error={error} onRetry={() => refetch()} />

  return (
    <div>
      <h1>Snippet 편집</h1>
      <SnippetForm
        mode="edit"
        initial={snippet}
        onSaved={(s) => navigate(`/snippets/${s.id}`)}
        onCancel={() => navigate(-1)}
      />
    </div>
  )
}
