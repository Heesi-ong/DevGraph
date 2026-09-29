import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getSnippet, getVersion, listVersions } from '../entities/snippet/api'
import { snippetKeys } from '../entities/snippet/queryKeys'
import { CopyCodeButton } from '../features/copy-snippet/CopyCodeButton'
import { StatusActions } from '../features/node-status/StatusActions'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'
import { CodeBlock } from '../widgets/CodeBlock'

function VersionHistory({ snippetId, language, currentVersionNo }: { snippetId: string; language: string; currentVersionNo: number }) {
  const [selected, setSelected] = useState<number | null>(null)
  const versions = useInfiniteQuery({
    queryKey: snippetKeys.versions(snippetId),
    queryFn: ({ pageParam }) => listVersions(snippetId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })
  const selectedVersion = useQuery({
    queryKey: snippetKeys.version(snippetId, selected ?? 0),
    queryFn: () => getVersion(snippetId, selected!),
    enabled: selected !== null,
  })

  const items = versions.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <section aria-label="버전 이력">
      <h2>버전 이력</h2>
      {versions.isLoading && <LoadingState />}
      {versions.error && <ErrorState error={versions.error} onRetry={() => versions.refetch()} />}
      <ol className="version-list" reversed>
        {items.map((v) => (
          <li key={v.versionNo}>
            <div className="row">
              <strong>v{v.versionNo}</strong>
              {v.versionNo === currentVersionNo && <span className="badge">현재</span>}
              <span className="muted small">{new Date(v.createdAt).toLocaleString('ko-KR')}</span>
              {v.changeSummary && <span>{v.changeSummary}</span>}
              <button
                type="button"
                aria-pressed={selected === v.versionNo}
                onClick={() => setSelected(selected === v.versionNo ? null : v.versionNo)}
              >
                {selected === v.versionNo ? '닫기' : `v${v.versionNo} 원문 보기`}
              </button>
            </div>
          </li>
        ))}
      </ol>
      {versions.hasNextPage && (
        <button type="button" onClick={() => versions.fetchNextPage()} disabled={versions.isFetchingNextPage}>
          더 보기
        </button>
      )}
      {selected !== null && (
        <div aria-label={`v${selected} 원문`}>
          {selectedVersion.isLoading && <LoadingState />}
          {selectedVersion.error && <ErrorState error={selectedVersion.error} />}
          {selectedVersion.data && (
            <>
              <h3>v{selectedVersion.data.versionNo} 원문</h3>
              <CodeBlock code={selectedVersion.data.code} language={language} />
              <CopyCodeButton code={selectedVersion.data.code} label={`v${selectedVersion.data.versionNo} 복사`} />
            </>
          )}
        </div>
      )}
    </section>
  )
}

export function SnippetDetailPage() {
  const { id = '' } = useParams()
  const { data: snippet, isLoading, error, refetch } = useQuery({
    queryKey: snippetKeys.detail(id),
    queryFn: () => getSnippet(id),
  })

  if (isLoading) return <LoadingState />
  if (error || !snippet) return <ErrorState error={error} onRetry={() => refetch()} />

  const { language, framework, currentVersionNo, useCount, secretScanStatus } = snippet.snippet

  return (
    <article>
      <div className="row">
        <span className="badge">{language}</span>
        {framework && <span className="badge">{framework}</span>}
        <h1>{snippet.title}</h1>
        <FavoriteButton nodeId={snippet.id} favorite={snippet.favorite} />
      </div>
      {snippet.status !== 'ACTIVE' && <p className="muted">상태: {snippet.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      {snippet.summary && <p className="muted">{snippet.summary}</p>}
      {snippet.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {snippet.tags.map((tag) => (
            <li key={tag.id} className="chip">
              <Link to={`/snippets?tagId=${tag.id}`}>{tag.name}</Link>
            </li>
          ))}
        </ul>
      )}
      {secretScanStatus === 'CONFIRMED_WITH_FINDINGS' && (
        <p className="muted small">secret으로 의심되는 값을 확인하고 저장한 코드입니다. 복사·공유 전에 다시 확인해 주세요.</p>
      )}

      <CodeBlock code={snippet.currentVersion.code} language={language} />
      <div className="row">
        <CopyCodeButton code={snippet.currentVersion.code} snippetId={snippet.id} />
        <span className="muted small">
          v{currentVersionNo} · 복사 {useCount}회
        </span>
      </div>

      <div className="row">
        {snippet.status !== 'TRASHED' && <Link to={`/snippets/${snippet.id}/edit`}>편집</Link>}
        <StatusActions node={snippet} />
      </div>

      <VersionHistory snippetId={snippet.id} language={language} currentVersionNo={currentVersionNo} />
      <p className="muted small">{new Date(snippet.updatedAt).toLocaleString('ko-KR')} 수정</p>
    </article>
  )
}
