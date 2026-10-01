import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { listErrors, listSolutions } from '../entities/problem/api'
import { problemKeys } from '../entities/problem/queryKeys'
import { RESOLUTION_LABEL, type ErrorFilters, type ResolutionStatus, type SolutionFilters } from '../entities/problem/types'
import { listProjects } from '../entities/project/api'
import { projectKeys } from '../entities/project/queryKeys'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { formatDateTime } from '../shared/format/datetime'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'

const STATUSES = Object.keys(RESOLUTION_LABEL) as ResolutionStatus[]

// 설계서 §10 Problems: Error/Solution 중심 탐색. 목록의 체인 미리보기(해결·프로젝트 수)로 끊긴 사슬을 바로 본다.
// 필터는 URL search params가 유일한 상태다(§16).
export function ProblemsPage() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'solutions' ? 'solutions' : 'errors'
  const resolution = (params.get('resolution')?.split(',').filter(Boolean) ?? []) as ResolutionStatus[]
  const projectId = params.get('projectId') || undefined

  function update(changes: Record<string, string | null>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next, { replace: true })
  }

  const projects = useQuery({
    queryKey: projectKeys.list({ projectStatus: [] }),
    queryFn: () => listProjects({ projectStatus: [] }, undefined, 100),
  })

  return (
    <div>
      <div className="row">
        <h1>Problems</h1>
        <Link to="/errors/new">새 Error</Link>
        <Link to="/solutions/new">새 Solution</Link>
      </div>
      <div className="tabs" role="tablist" aria-label="보기">
        <button type="button" role="tab" aria-selected={tab === 'errors'} onClick={() => update({ tab: null })}>
          Errors
        </button>
        <button type="button" role="tab" aria-selected={tab === 'solutions'} onClick={() => update({ tab: 'solutions' })}>
          Solutions
        </button>
      </div>
      <div className="row" role="group" aria-label="필터">
        {tab === 'errors' &&
          STATUSES.map((s) => (
            <label key={s}>
              <input
                type="checkbox"
                checked={resolution.includes(s)}
                onChange={() =>
                  update({ resolution: (resolution.includes(s) ? resolution.filter((v) => v !== s) : [...resolution, s]).join(',') || null })
                }
              />{' '}
              {RESOLUTION_LABEL[s]}
            </label>
          ))}
        <label>
          프로젝트
          <select value={projectId ?? ''} onChange={(e) => update({ projectId: e.target.value || null })}>
            <option value="">전체</option>
            {(projects.data?.items ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {p.title}
              </option>
            ))}
          </select>
        </label>
      </div>
      {tab === 'errors' ? <ErrorList filters={{ resolution, projectId }} /> : <SolutionList filters={{ projectId }} />}
    </div>
  )
}

function ErrorList({ filters }: { filters: ErrorFilters }) {
  const q = useInfiniteQuery({
    queryKey: problemKeys.errors(filters),
    queryFn: ({ pageParam }) => listErrors(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })
  const items = q.data?.pages.flatMap((p) => p.items) ?? []
  return (
    <>
      {q.isLoading && <LoadingState />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && items.length === 0 && (
        <EmptyState
          title="조건에 맞는 Error가 없습니다"
          description="막혔던 오류를 기록해 두면 다음에 같은 오류를 바로 찾을 수 있어요."
          action={<Link to="/errors/new">첫 Error 기록하기</Link>}
        />
      )}
      {items.length > 0 && (
        <ul className="node-list" aria-label="Error 목록">
          {items.map((e) => {
            const unresolved = e.resolutionStatus === 'OPEN' || e.resolutionStatus === 'INVESTIGATING'
            return (
              <li key={e.id} className="node-card">
                <div className="row">
                  <span className="badge">{RESOLUTION_LABEL[e.resolutionStatus]}</span>
                  <Link to={`/errors/${e.id}`} className="node-title">
                    {e.title}
                  </Link>
                  <FavoriteButton nodeId={e.id} favorite={e.favorite} />
                </div>
                <p className="muted small mono">{e.messagePreview}</p>
                <p className="muted small">
                  해결 {e.solutionCount} · 프로젝트 {e.projectCount} · 발생 {formatDateTime(e.occurredAt)}
                  {unresolved && e.solutionCount === 0 && ' · 아직 해결 방법이 없습니다'}
                  {e.resolutionStatus === 'RESOLVED' && e.solutionCount === 0 && ' · 해결 방법이 연결되지 않았습니다'}
                </p>
              </li>
            )
          })}
        </ul>
      )}
      {q.hasNextPage && (
        <button type="button" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
          더 보기
        </button>
      )}
    </>
  )
}

function SolutionList({ filters }: { filters: SolutionFilters }) {
  const q = useInfiniteQuery({
    queryKey: problemKeys.solutions(filters),
    queryFn: ({ pageParam }) => listSolutions(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })
  const items = q.data?.pages.flatMap((p) => p.items) ?? []
  return (
    <>
      {q.isLoading && <LoadingState />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && items.length === 0 && <EmptyState title="조건에 맞는 Solution이 없습니다" />}
      {items.length > 0 && (
        <ul className="node-list" aria-label="Solution 목록">
          {items.map((s) => (
            <li key={s.id} className="node-card">
              <div className="row">
                <Link to={`/solutions/${s.id}`} className="node-title">
                  {s.title}
                </Link>
                <FavoriteButton nodeId={s.id} favorite={s.favorite} />
              </div>
              <p className="muted small">{s.approachPreview}</p>
              <p className="muted small">
                해결하는 오류 {s.errorCount} · 코드 {s.snippetCount} · 프로젝트 {s.projectCount}
              </p>
            </li>
          ))}
        </ul>
      )}
      {q.hasNextPage && (
        <button type="button" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
          더 보기
        </button>
      )}
    </>
  )
}
