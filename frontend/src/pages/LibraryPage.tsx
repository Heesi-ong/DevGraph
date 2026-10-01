import { useInfiniteQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { listNodes } from '../entities/knowledge-node/api'
import { nodeKeys } from '../entities/knowledge-node/queryKeys'
import type { AnyNodeType, NodeFilters, NodeStatus } from '../entities/knowledge-node/types'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'
import { NodeList } from '../widgets/NodeList'

// Library(§8): Concepts, Notes, Errors, Solutions, Resources. Snippet과 Project는 각자의 화면이 있다.
const LIBRARY_TYPES: AnyNodeType[] = ['CONCEPT', 'NOTE', 'ERROR', 'SOLUTION', 'RESOURCE']

// 필터는 URL search params가 유일한 상태다(§16). 새로고침·공유 링크에서도 같은 목록이 나온다.
function readFilters(params: URLSearchParams): NodeFilters {
  const type = params.get('type')
  const status = params.get('status')
  return {
    type: LIBRARY_TYPES.includes(type as AnyNodeType) ? (type as AnyNodeType) : undefined,
    status: status === 'ARCHIVED' || status === 'TRASHED' ? (status as NodeStatus) : undefined,
    tagId: params.get('tagId') || undefined,
    favorite: params.get('favorite') === 'true' ? true : undefined,
  }
}

export function LibraryPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)

  const { data, isLoading, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: nodeKeys.list(filters),
    queryFn: ({ pageParam }) => listNodes(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  const nodes = data?.pages.flatMap((p) => p.items) ?? []

  return (
    <div>
      <div className="row">
        <h1>Library</h1>
        <Link to="/nodes/new">새로 만들기</Link>
      </div>
      <div className="row" role="group" aria-label="필터">
        <select value={filters.type ?? ''} onChange={(e) => setParam('type', e.target.value)} aria-label="유형">
          <option value="">모든 유형</option>
          <option value="CONCEPT">Concept</option>
          <option value="NOTE">Note</option>
          <option value="ERROR">Error</option>
          <option value="SOLUTION">Solution</option>
          <option value="RESOURCE">Resource</option>
        </select>
        <select value={filters.status ?? ''} onChange={(e) => setParam('status', e.target.value)} aria-label="상태">
          <option value="">활성</option>
          <option value="ARCHIVED">보관함</option>
          <option value="TRASHED">휴지통</option>
        </select>
        <label>
          <input
            type="checkbox"
            checked={filters.favorite === true}
            onChange={(e) => setParam('favorite', e.target.checked ? 'true' : '')}
          />{' '}
          즐겨찾기만
        </label>
        {filters.tagId && (
          <button type="button" onClick={() => setParam('tagId', '')}>
            태그 필터 해제
          </button>
        )}
      </div>

      {isLoading && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => refetch()} />}
      {data && nodes.length === 0 && (
        <EmptyState title="조건에 맞는 항목이 없습니다" description="필터를 바꾸거나 새 지식을 만들어 보세요." />
      )}
      {nodes.length > 0 && <NodeList nodes={nodes} />}
      {hasNextPage && (
        <button type="button" onClick={() => fetchNextPage()} disabled={isFetchingNextPage}>
          {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
        </button>
      )}
    </div>
  )
}
