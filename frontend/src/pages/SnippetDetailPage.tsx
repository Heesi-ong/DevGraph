import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getDiff, getSnippet, getVersion, listVersions } from '../entities/snippet/api'
import { snippetKeys } from '../entities/snippet/queryKeys'
import { CopyCodeButton } from '../features/copy-snippet/CopyCodeButton'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { StatusActions } from '../features/node-status/StatusActions'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { toApiError } from '../shared/api/errors'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'
import { CodeBlock } from '../widgets/CodeBlock'

// 설계서 §14.4/SNP-07. 색에만 기대지 않도록 줄마다 +/- 기호와 줄 번호를 텍스트로 보여 준다.
function VersionDiff({ snippetId, versionNos }: { snippetId: string; versionNos: number[] }) {
  const newest = versionNos[0]
  const [from, setFrom] = useState<number>(versionNos[1] ?? newest)
  const [to, setTo] = useState<number>(newest)
  const [requested, setRequested] = useState<{ from: number; to: number } | null>(null)
  const diff = useQuery({
    queryKey: snippetKeys.diff(snippetId, requested?.from ?? 0, requested?.to ?? 0),
    queryFn: () => getDiff(snippetId, requested!.from, requested!.to),
    enabled: requested !== null,
    retry: false,
  })
  if (versionNos.length < 2) return null
  const tooLarge = diff.error && toApiError(diff.error).code === 'DIFF_TOO_LARGE'

  return (
    <section aria-label="버전 비교">
      <h3>버전 비교</h3>
      <div className="row">
        <label>
          이전 버전
          <select value={from} onChange={(e) => setFrom(Number(e.target.value))}>
            {versionNos.map((n) => (
              <option key={n} value={n}>v{n}</option>
            ))}
          </select>
        </label>
        <label>
          이후 버전
          <select value={to} onChange={(e) => setTo(Number(e.target.value))}>
            {versionNos.map((n) => (
              <option key={n} value={n}>v{n}</option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => setRequested({ from, to })}>
          비교
        </button>
      </div>
      {diff.isLoading && <LoadingState />}
      {tooLarge && <p role="alert" className="error-text">변경이 너무 커서 비교할 수 없습니다. 각 버전의 원문을 열어 확인해 주세요.</p>}
      {diff.error && !tooLarge && <ErrorState error={diff.error} onRetry={() => diff.refetch()} />}
      {diff.data && (
        <div aria-label={`v${diff.data.from}에서 v${diff.data.to}로 변경`}>
          <p role="status">
            v{diff.data.from} → v{diff.data.to}: <strong>+{diff.data.added}</strong>줄 추가, <strong>-{diff.data.deleted}</strong>줄 삭제
          </p>
          {diff.data.hunks.length === 0 && <p className="muted">두 버전의 코드가 같습니다.</p>}
          {diff.data.hunks.map((h) => (
            <table key={`${h.oldStart}-${h.newStart}`} className="diff-table" aria-label={`변경 구간 이전 ${h.oldStart}행부터`}>
              <caption className="muted small mono">
                @@ -{h.oldStart},{h.oldLines} +{h.newStart},{h.newLines} @@
              </caption>
              <tbody>
                {h.lines.map((l, i) => (
                  <tr key={i} className={`diff-${l.type.toLowerCase()}`}>
                    <td className="diff-no" aria-label="이전 줄 번호">{l.oldNo || ''}</td>
                    <td className="diff-no" aria-label="이후 줄 번호">{l.newNo || ''}</td>
                    <td className="diff-mark" aria-label={l.type === 'ADD' ? '추가' : l.type === 'DELETE' ? '삭제' : '유지'}>
                      {l.type === 'ADD' ? '+' : l.type === 'DELETE' ? '-' : ' '}
                    </td>
                    <td className="diff-text">
                      {l.text.replace(/\r$/, '')}
                      {l.text.endsWith('\r') && <span className="muted" title="줄 끝이 CRLF입니다"> ␍</span>}
                      {l.noEol && <span className="muted"> ⏎없음</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        </div>
      )}
    </section>
  )
}

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
      <VersionDiff snippetId={snippetId} versionNos={items.map((v) => v.versionNo)} />
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

      <RelatedNodes nodeId={snippet.id} nodeType="SNIPPET" relations={snippet.relations} editable={snippet.status !== 'TRASHED'} />
      <VersionHistory snippetId={snippet.id} language={language} currentVersionNo={currentVersionNo} />
      <p className="muted small">{new Date(snippet.updatedAt).toLocaleString('ko-KR')} 수정</p>
    </article>
  )
}
