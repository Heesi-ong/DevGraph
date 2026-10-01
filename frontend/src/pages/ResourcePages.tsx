import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { createResource, getResource, listResources, updateResource } from '../entities/resource/api'
import { resourceKeys } from '../entities/resource/queryKeys'
import { RESOURCE_KINDS, type ResourceDetail, type ResourceFilters, type ResourceKind } from '../entities/resource/types'
import type { Tag } from '../entities/tag/types'
import { SubtypeFormFrame } from '../features/edit-subtype/SubtypeFormFrame'
import { StatusActions } from '../features/node-status/StatusActions'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { useInvalidateRelationViews } from '../features/relations/useInvalidateRelationViews'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'

// 외부 링크는 새 창임을 글자로 알리고 rel="noopener noreferrer"를 쓴다(§17.4). 서버는 URL 내용을 가져오지 않는다.
function ExternalLink({ url }: { url: string }) {
  return (
    <a href={url} target="_blank" rel="noopener noreferrer">
      {url} (새 창)
    </a>
  )
}

export function ResourceListPage() {
  const [params, setParams] = useSearchParams()
  const kinds = (params.get('kind')?.split(',').filter(Boolean) ?? []) as ResourceKind[]
  const filters: ResourceFilters = { kinds }
  const q = useInfiniteQuery({
    queryKey: resourceKeys.list(filters),
    queryFn: ({ pageParam }) => listResources(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })
  const items = q.data?.pages.flatMap((p) => p.items) ?? []

  function toggle(kind: ResourceKind) {
    const next = new URLSearchParams(params)
    const list = kinds.includes(kind) ? kinds.filter((k) => k !== kind) : [...kinds, kind]
    if (list.length) next.set('kind', list.join(','))
    else next.delete('kind')
    setParams(next, { replace: true })
  }

  return (
    <div>
      <div className="row">
        <h1>Resources</h1>
        <Link to="/resources/new">새 Resource</Link>
      </div>
      <div className="row" role="group" aria-label="종류 필터">
        {RESOURCE_KINDS.map((k) => (
          <label key={k}>
            <input type="checkbox" checked={kinds.includes(k)} onChange={() => toggle(k)} /> {k}
          </label>
        ))}
      </div>
      {q.isLoading && <LoadingState />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && items.length === 0 && (
        <EmptyState
          title="저장한 Resource가 없습니다"
          description="공부에 쓴 문서·글·저장소 링크를 모아 두고 지식과 연결해 보세요."
          action={<Link to="/resources/new">첫 Resource 저장하기</Link>}
        />
      )}
      {items.length > 0 && (
        <ul className="node-list" aria-label="Resource 목록">
          {items.map((r) => (
            <li key={r.id} className="node-card">
              <div className="row">
                <span className="badge">{r.kind}</span>
                <Link to={`/resources/${r.id}`} className="node-title">
                  {r.title}
                </Link>
                <FavoriteButton nodeId={r.id} favorite={r.favorite} />
              </div>
              <p className="muted small">{r.siteName ?? r.url}</p>
              {r.summary && <p className="muted">{r.summary}</p>}
            </li>
          ))}
        </ul>
      )}
      {q.hasNextPage && (
        <button type="button" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
          더 보기
        </button>
      )}
    </div>
  )
}

export function ResourceDetailPage() {
  const { id = '' } = useParams()
  const { data: resource, isLoading, error, refetch } = useQuery({
    queryKey: resourceKeys.detail(id),
    queryFn: () => getResource(id),
  })
  if (isLoading) return <LoadingState />
  if (error || !resource) return <ErrorState error={error} onRetry={() => refetch()} />

  const editable = resource.status !== 'TRASHED'
  return (
    <article>
      <div className="row">
        <span className="badge">{resource.resource.kind}</span>
        <h1>{resource.title}</h1>
        <FavoriteButton nodeId={resource.id} favorite={resource.favorite} />
      </div>
      {resource.status !== 'ACTIVE' && <p className="muted">상태: {resource.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      <p>
        <ExternalLink url={resource.resource.url} />
      </p>
      {resource.resource.siteName && <p className="muted small">{resource.resource.siteName}</p>}
      {resource.summary && <p className="muted">{resource.summary}</p>}
      {resource.duplicates.length > 0 && (
        <p role="status" className="warning-banner">
          같은 주소의 Resource가 이미 있습니다:{' '}
          {resource.duplicates.map((d, i) => (
            <span key={d.id}>
              {i > 0 && ', '}
              <Link to={`/resources/${d.id}`}>{d.title}</Link>
            </span>
          ))}
          . 다른 맥락으로 기록한 것이라면 그대로 두어도 됩니다.
        </p>
      )}
      {resource.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {resource.tags.map((tag) => (
            <li key={tag.id} className="chip">
              {tag.name}
            </li>
          ))}
        </ul>
      )}
      <div className="row">
        {editable && <Link to={`/resources/${resource.id}/edit`}>편집</Link>}
        <StatusActions node={resource} />
      </div>
      <RelatedNodes nodeId={resource.id} nodeType="RESOURCE" relations={resource.relations} editable={editable} />
    </article>
  )
}

export function ResourceFormPage() {
  const { id } = useParams()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: resourceKeys.detail(id ?? ''),
    queryFn: () => getResource(id!),
    enabled: !!id,
  })
  if (id && isLoading) return <LoadingState />
  if (id && (error || !data)) return <ErrorState error={error} onRetry={() => refetch()} />
  return <ResourceForm initial={data} />
}

function ResourceForm({ initial }: { initial?: ResourceDetail }) {
  const navigate = useNavigate()
  const invalidate = useInvalidateRelationViews()
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [url, setUrl] = useState(initial?.resource.url ?? '')
  const [kind, setKind] = useState<ResourceKind>(initial?.resource.kind ?? 'WEB')
  const [siteName, setSiteName] = useState(initial?.resource.siteName ?? '')
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])

  const dirty = initial
    ? title !== initial.title || summary !== (initial.summary ?? '') || url !== initial.resource.url ||
      kind !== initial.resource.kind || siteName !== (initial.resource.siteName ?? '') ||
      tags.map((t) => t.id).join() !== initial.tags.map((t) => t.id).join()
    : !!(title || summary || url || siteName)

  return (
    <div>
      <h1>{initial ? 'Resource 편집' : '새 Resource'}</h1>
      <SubtypeFormFrame
        title={title}
        onTitle={setTitle}
        summary={summary}
        onSummary={setSummary}
        tags={tags}
        onTags={setTags}
        dirty={dirty}
        save={async () => {
          const input = { title, summary, url, kind, siteName, tagIds: tags.map((t) => t.id) }
          const saved = initial ? await updateResource(initial.id, initial.version, input) : await createResource(input)
          await invalidate()
          return saved
        }}
        onSaved={(saved) => navigate(`/resources/${saved.id}`)}
        onCancel={() => navigate(-1)}
      >
        <label>
          URL
          <input value={url} onChange={(e) => setUrl(e.target.value)} required placeholder="https://…" maxLength={2000} inputMode="url" />
        </label>
        <p className="muted small">http와 https 주소만 저장할 수 있습니다. 서버는 이 주소를 열어 보지 않습니다.</p>
        <div className="row">
          <label>
            종류
            <select value={kind} onChange={(e) => setKind(e.target.value as ResourceKind)}>
              {RESOURCE_KINDS.map((k) => (
                <option key={k} value={k}>
                  {k}
                </option>
              ))}
            </select>
          </label>
          <label>
            사이트 이름 (선택)
            <input value={siteName} onChange={(e) => setSiteName(e.target.value)} maxLength={200} />
          </label>
        </div>
      </SubtypeFormFrame>
    </div>
  )
}
