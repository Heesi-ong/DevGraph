import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { listNodes } from '../entities/knowledge-node/api'
import type { AnyNodeType } from '../entities/knowledge-node/types'
import { nodePath } from '../entities/relation/paths'
import { search } from '../entities/search/api'
import { isSearchable } from '../entities/search/query'
import { searchKeys } from '../entities/search/queryKeys'
import type { MatchedField, SearchFilters, SearchHit } from '../entities/search/types'
import { listSnippets } from '../entities/snippet/api'
import { HighlightedText } from '../features/search/HighlightedText'
import { clearRecentSearches, loadRecentSearches, rememberSearch } from '../features/search/recentSearches'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'

const TYPES: AnyNodeType[] = ['CONCEPT', 'NOTE', 'SNIPPET', 'ERROR', 'SOLUTION', 'PROJECT', 'RESOURCE']
const FIELD_LABEL: Record<MatchedField, string> = { title: '제목', tag: '태그', language: '언어', body: '본문', code: '코드', error: '오류 메시지' }

function readFilters(params: URLSearchParams): SearchFilters {
  return {
    q: (params.get('q') ?? '').trim(),
    types: (params.get('types')?.split(',').filter(Boolean) ?? []) as AnyNodeType[],
    language: params.get('language') || undefined,
    framework: params.get('framework') || undefined,
    archived: params.get('archived') === 'true',
    history: params.get('scope') === 'snippetHistory',
  }
}

export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const [input, setInput] = useState(filters.q)
  useEffect(() => setInput(filters.q), [filters.q])
  const container = useRef<HTMLDivElement>(null)
  const searchable = isSearchable(filters.q)

  const results = useInfiniteQuery({
    queryKey: searchKeys.results(filters),
    queryFn: ({ pageParam }) => search(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
    enabled: searchable,
  })

  function update(changes: Record<string, string | null>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next, { replace: true })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const q = input.trim()
    if (isSearchable(q)) rememberSearch(q)
    update({ q: q || null })
  }

  // 결과 목록의 키보드 탐색: 입력창·결과 링크 사이를 ↑/↓로 이동하고 Enter로 연다(링크의 기본 동작).
  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const stops = Array.from(container.current?.querySelectorAll<HTMLElement>('[data-nav-stop]') ?? [])
    const index = stops.indexOf(document.activeElement as HTMLElement)
    if (index === -1 && event.key === 'ArrowUp') return
    const next = event.key === 'ArrowDown' ? Math.min(stops.length - 1, index + 1) : Math.max(0, index - 1)
    if (stops[next]) {
      event.preventDefault()
      stops[next].focus()
    }
  }

  const items = results.data?.pages.flatMap((p) => p.items) ?? []
  const fallback = results.data?.pages[0]?.fallback ?? null
  const hasFilters = filters.types.length > 0 || !!filters.language || !!filters.framework || filters.archived || filters.history

  return (
    <div ref={container} onKeyDown={onKeyDown}>
      <h1>검색</h1>
      <form onSubmit={submit} role="search" className="row">
        <input
          data-nav-stop
          type="search"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          aria-label="검색어"
          placeholder="제목, 태그, 본문, 코드로 검색"
          maxLength={200}
        />
        <button type="submit">검색</button>
      </form>

      <fieldset className="graph-filters">
        <legend>필터</legend>
        <div className="row" role="group" aria-label="항목 유형">
          {TYPES.map((type) => (
            <label key={type}>
              <input
                type="checkbox"
                checked={filters.types.includes(type)}
                onChange={() =>
                  update({
                    types: (filters.types.includes(type)
                      ? filters.types.filter((t) => t !== type)
                      : [...filters.types, type]
                    ).join(',') || null,
                  })
                }
              />{' '}
              {type}
            </label>
          ))}
          <label>
            언어
            <input
              defaultValue={filters.language ?? ''}
              key={`lang-${filters.language ?? ''}`}
              onBlur={(e) => update({ language: e.target.value.trim().toLowerCase() || null })}
              onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
              maxLength={30}
            />
          </label>
          <label>
            framework
            <input
              defaultValue={filters.framework ?? ''}
              key={`fw-${filters.framework ?? ''}`}
              onBlur={(e) => update({ framework: e.target.value.trim().toLowerCase() || null })}
              onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
              maxLength={50}
            />
          </label>
          <label>
            <input
              type="checkbox"
              checked={filters.archived}
              onChange={(e) => update({ archived: e.target.checked ? 'true' : null })}
            />{' '}
            보관된 항목 포함
          </label>
          <label>
            <input
              type="checkbox"
              checked={filters.history}
              onChange={(e) => update({ scope: e.target.checked ? 'snippetHistory' : null })}
            />{' '}
            Snippet 과거 버전 코드 검색
          </label>
          {hasFilters && (
            <button type="button" onClick={() => update({ types: null, language: null, framework: null, archived: null, scope: null })}>
              필터 해제
            </button>
          )}
        </div>
      </fieldset>

      {!searchable && <StartView q={filters.q} onPick={(q) => { setInput(q); update({ q }) }} />}
      {searchable && results.isLoading && <LoadingState label="검색 중..." />}
      {searchable && results.error && <ErrorState error={results.error} onRetry={() => results.refetch()} />}

      {searchable && results.data && items.length > 0 && (
        <ul className="node-list" aria-label="검색 결과">
          {items.map((hit) => (
            <ResultItem key={hit.id} hit={hit} />
          ))}
        </ul>
      )}
      {results.hasNextPage && (
        <button type="button" onClick={() => results.fetchNextPage()} disabled={results.isFetchingNextPage}>
          {results.isFetchingNextPage ? '불러오는 중…' : '더 보기'}
        </button>
      )}

      {searchable && results.data && items.length === 0 && (
        <section aria-label="결과 없음">
          <EmptyState title={`“${filters.q}”에 대한 결과가 없습니다`} />
          {fallback?.unfilteredCount != null && fallback.unfilteredCount > 0 && (
            <p>
              필터를 풀면 {fallback.unfilteredCount}건이 있습니다.{' '}
              <button type="button" onClick={() => update({ types: null, language: null, framework: null })}>
                필터 해제
              </button>
            </p>
          )}
          {fallback && fallback.similar.length > 0 && (
            <>
              <h2>비슷한 항목</h2>
              <ul className="node-list" aria-label="유사 결과">
                {fallback.similar.map((hit) => (
                  <ResultItem key={hit.id} hit={hit} />
                ))}
              </ul>
            </>
          )}
          {fallback && fallback.recent.length > 0 && (
            <>
              <h2>최근 항목</h2>
              <ul className="node-list" aria-label="최근 항목">
                {fallback.recent.map((item) => (
                  <li key={item.id} className="node-card">
                    <span className="badge">{item.type}</span>{' '}
                    <Link data-nav-stop to={nodePath(item.type, item.id)}>
                      {item.title}
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </div>
  )
}

function ResultItem({ hit }: { hit: SearchHit }) {
  return (
    <li className="node-card">
      <div className="row">
        <span className="badge">{hit.type}</span>
        {hit.language && <span className="badge">{hit.language}</span>}
        {hit.framework && <span className="badge">{hit.framework}</span>}
        <Link data-nav-stop to={nodePath(hit.type, hit.id)} className="node-title">
          <HighlightedText segments={hit.highlight.title ?? [{ text: hit.title, matched: false }]} />
        </Link>
        {hit.status === 'ARCHIVED' && <span className="muted small">(보관됨)</span>}
      </div>
      {hit.highlight.summary && (
        <p className="muted">
          <HighlightedText segments={hit.highlight.summary} />
        </p>
      )}
      {hit.highlight.body && (
        <p className="muted small search-excerpt">
          <HighlightedText segments={hit.highlight.body} />
        </p>
      )}
      {hit.highlight.error && (
        <pre className="search-code" aria-label="오류 메시지 발췌">
          <HighlightedText segments={hit.highlight.error} />
        </pre>
      )}
      {hit.highlight.code && (
        <pre className="search-code">
          <HighlightedText segments={hit.highlight.code} />
        </pre>
      )}
      <p className="muted small">
        {hit.versionNo != null && <strong>v{hit.versionNo}(과거 버전)에서 일치 · </strong>}
        일치: {hit.matchedFields.map((f) => FIELD_LABEL[f]).join(', ') || '유사'} · {new Date(hit.updatedAt).toLocaleDateString('ko-KR')}
      </p>
    </li>
  )
}

// 2자 미만 입력은 서버 검색을 하지 않고 최근 검색어와 최근 항목을 보여 준다(§9.5 검색 흐름 1).
function StartView({ q, onPick }: { q: string; onPick: (q: string) => void }) {
  const [recent, setRecent] = useState(loadRecentSearches)
  const items = useQuery({
    queryKey: ['search', 'start-items'],
    queryFn: async () => {
      const [nodes, snippets] = await Promise.all([listNodes({}, undefined, 5), listSnippets({}, undefined, 5)])
      return [
        ...nodes.items.map((n) => ({ id: n.id, type: n.type as AnyNodeType, title: n.title, updatedAt: n.updatedAt })),
        ...snippets.items.map((s) => ({ id: s.id, type: 'SNIPPET' as AnyNodeType, title: s.title, updatedAt: s.updatedAt })),
      ]
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
        .slice(0, 5)
    },
  })
  return (
    <section aria-label="검색 시작">
      <p className="muted">{q ? '검색어는 2자 이상 입력해 주세요.' : '찾고 싶은 지식이나 코드를 입력해 보세요.'}</p>
      {recent.length > 0 && (
        <>
          <div className="row">
            <h2>최근 검색어</h2>
            <button type="button" onClick={() => { clearRecentSearches(); setRecent([]) }}>
              지우기
            </button>
          </div>
          <ul className="candidate-list" aria-label="최근 검색어">
            {recent.map((r) => (
              <li key={r}>
                <button type="button" data-nav-stop onClick={() => onPick(r)}>
                  {r}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {items.data && items.data.length > 0 && (
        <>
          <h2>최근 항목</h2>
          <ul className="node-list" aria-label="최근 항목">
            {items.data.map((item) => (
              <li key={item.id} className="node-card">
                <span className="badge">{item.type}</span>{' '}
                <Link data-nav-stop to={nodePath(item.type, item.id)}>
                  {item.title}
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
