import { useInfiniteQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import type { NodeStatus } from '../entities/knowledge-node/types'
import { listSnippets } from '../entities/snippet/api'
import { snippetKeys } from '../entities/snippet/queryKeys'
import type { SnippetFilters } from '../entities/snippet/types'
import { COMMON_LANGUAGES } from '../shared/editor/languages'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'
import { SnippetList } from '../widgets/SnippetList'

// 필터는 URL search params가 유일한 상태다(§16). 새로고침·공유 링크에서도 같은 목록이 나온다.
function readFilters(params: URLSearchParams): SnippetFilters {
  const status = params.get('status')
  return {
    language: params.get('language') || undefined,
    framework: params.get('framework') || undefined,
    tagId: params.get('tagId') || undefined,
    status: status === 'ARCHIVED' || status === 'TRASHED' ? (status as NodeStatus) : undefined,
    favorite: params.get('favorite') === 'true' ? true : undefined,
  }
}

export function SnippetListPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const [framework, setFramework] = useState(filters.framework ?? '')

  const { data, isLoading, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } = useInfiniteQuery({
    queryKey: snippetKeys.list(filters),
    queryFn: ({ pageParam }) => listSnippets(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  function applyFramework(event: FormEvent) {
    event.preventDefault()
    setParam('framework', framework.trim().toLowerCase())
  }

  const snippets = data?.pages.flatMap((p) => p.items) ?? []

  return (
    <div>
      <div className="row">
        <h1>Snippets</h1>
        <Link to="/snippets/new">새 Snippet</Link>
      </div>
      <div className="row" role="group" aria-label="필터">
        <select value={filters.language ?? ''} onChange={(e) => setParam('language', e.target.value)} aria-label="언어">
          <option value="">모든 언어</option>
          {COMMON_LANGUAGES.map((l) => (
            <option key={l} value={l}>
              {l}
            </option>
          ))}
          {filters.language && !COMMON_LANGUAGES.includes(filters.language) && (
            <option value={filters.language}>{filters.language}</option>
          )}
        </select>
        <form onSubmit={applyFramework} className="row">
          <input
            value={framework}
            onChange={(e) => setFramework(e.target.value)}
            placeholder="framework"
            aria-label="framework"
            maxLength={50}
          />
          <button type="submit">적용</button>
        </form>
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
      {data && snippets.length === 0 && (
        <EmptyState
          title="조건에 맞는 Snippet이 없습니다"
          description="다시 쓰고 싶은 코드를 저장해 두면 언어·태그로 바로 찾을 수 있어요."
          action={<Link to="/snippets/new">첫 Snippet 만들기</Link>}
        />
      )}
      {snippets.length > 0 && <SnippetList snippets={snippets} />}
      {hasNextPage && (
        <button type="button" onClick={() => fetchNextPage()} disabled={isFetchingNextPage}>
          {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
        </button>
      )}
    </div>
  )
}
