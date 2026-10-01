import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { createSolution, getSolution, listErrors, updateSolution } from '../entities/problem/api'
import { problemKeys } from '../entities/problem/queryKeys'
import type { SolutionDetail } from '../entities/problem/types'
import type { Tag } from '../entities/tag/types'
import { SubtypeFormFrame } from '../features/edit-subtype/SubtypeFormFrame'
import { StatusActions } from '../features/node-status/StatusActions'
import { ProblemChain } from '../features/problem-chain/ProblemChain'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { useInvalidateRelationViews } from '../features/relations/useInvalidateRelationViews'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { formatDateTime } from '../shared/format/datetime'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'
import { MarkdownView } from '../widgets/MarkdownView'

export function SolutionDetailPage() {
  const { id = '' } = useParams()
  const { data: solution, isLoading, error, refetch } = useQuery({
    queryKey: problemKeys.solution(id),
    queryFn: () => getSolution(id),
  })
  if (isLoading) return <LoadingState />
  if (error || !solution) return <ErrorState error={error} onRetry={() => refetch()} />

  const editable = solution.status !== 'TRASHED'
  const s = solution.solution
  return (
    <article>
      <div className="row">
        <span className="badge">SOLUTION</span>
        <h1>{solution.title}</h1>
        <FavoriteButton nodeId={solution.id} favorite={solution.favorite} />
      </div>
      {solution.status !== 'ACTIVE' && <p className="muted">상태: {solution.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      {solution.summary && <p className="muted">{solution.summary}</p>}
      <h2>접근 방법</h2>
      <MarkdownView source={s.approachMd} />
      {s.stepsMd && (
        <>
          <h2>적용 단계</h2>
          <MarkdownView source={s.stepsMd} />
        </>
      )}
      {s.verificationMd && (
        <>
          <h2>검증</h2>
          <MarkdownView source={s.verificationMd} />
        </>
      )}
      {s.tradeoffsMd && (
        <>
          <h2>트레이드오프</h2>
          <MarkdownView source={s.tradeoffsMd} />
        </>
      )}
      {s.resolvedAt && <p className="muted small">해결 시각 {formatDateTime(s.resolvedAt)}</p>}
      {solution.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {solution.tags.map((tag) => (
            <li key={tag.id} className="chip">
              {tag.name}
            </li>
          ))}
        </ul>
      )}
      <ProblemChain nodeId={solution.id} nodeType="SOLUTION" relations={solution.relations} editable={editable} />
      <div className="row">
        {editable && <Link to={`/solutions/${solution.id}/edit`}>편집</Link>}
        <StatusActions node={solution} />
      </div>
      <RelatedNodes nodeId={solution.id} nodeType="SOLUTION" relations={solution.relations} editable={editable} showPicker={false} />
    </article>
  )
}

export function SolutionFormPage() {
  const { id } = useParams()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: problemKeys.solution(id ?? ''),
    queryFn: () => getSolution(id!),
    enabled: !!id,
  })
  if (id && isLoading) return <LoadingState />
  if (id && (error || !data)) return <ErrorState error={error} onRetry={() => refetch()} />
  return <SolutionForm initial={data} />
}

function SolutionForm({ initial }: { initial?: SolutionDetail }) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const invalidate = useInvalidateRelationViews()
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [approach, setApproach] = useState(initial?.solution.approachMd ?? '')
  const [steps, setSteps] = useState(initial?.solution.stepsMd ?? '')
  const [verification, setVerification] = useState(initial?.solution.verificationMd ?? '')
  const [tradeoffs, setTradeoffs] = useState(initial?.solution.tradeoffsMd ?? '')
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])
  // 새로 만들 때만: 해결하는 Error를 고르면 서버가 Solution과 SOLVED_BY를 한 번에 만든다.
  const [errorNodeId, setErrorNodeId] = useState(params.get('errorId') ?? '')
  const errors = useQuery({
    queryKey: problemKeys.errors({ resolution: ['OPEN', 'INVESTIGATING', 'RESOLVED'] }),
    queryFn: () => listErrors({ resolution: ['OPEN', 'INVESTIGATING', 'RESOLVED'] }, undefined, 100),
    enabled: !initial,
  })

  const dirty = initial
    ? title !== initial.title || summary !== (initial.summary ?? '') || approach !== initial.solution.approachMd ||
      steps !== (initial.solution.stepsMd ?? '') || verification !== (initial.solution.verificationMd ?? '') ||
      tradeoffs !== (initial.solution.tradeoffsMd ?? '') || tags.map((t) => t.id).join() !== initial.tags.map((t) => t.id).join()
    : !!(title || summary || approach || steps || verification || tradeoffs)

  return (
    <div>
      <h1>{initial ? 'Solution 편집' : '새 Solution'}</h1>
      <SubtypeFormFrame
        title={title}
        onTitle={setTitle}
        summary={summary}
        onSummary={setSummary}
        tags={tags}
        onTags={setTags}
        dirty={dirty}
        save={async () => {
          const input = {
            title, summary, approachMd: approach, stepsMd: steps, verificationMd: verification,
            tradeoffsMd: tradeoffs, tagIds: tags.map((t) => t.id),
          }
          const saved = initial
            ? await updateSolution(initial.id, initial.version, input)
            : await createSolution({ ...input, errorNodeId: errorNodeId || undefined })
          await invalidate()
          return saved
        }}
        onSaved={(saved) => navigate(`/solutions/${saved.id}`)}
        onCancel={() => navigate(-1)}
      >
        {!initial && (
          <label>
            해결하는 Error (선택)
            <select value={errorNodeId} onChange={(e) => setErrorNodeId(e.target.value)}>
              <option value="">연결하지 않음</option>
              {(errors.data?.items ?? []).map((e) => (
                <option key={e.id} value={e.id}>
                  {e.title}
                </option>
              ))}
              {errorNodeId && !(errors.data?.items ?? []).some((e) => e.id === errorNodeId) && (
                <option value={errorNodeId}>선택한 Error</option>
              )}
            </select>
          </label>
        )}
        <label>
          접근 방법 (Markdown)
          <textarea value={approach} onChange={(e) => setApproach(e.target.value)} rows={4} required />
        </label>
        <label>
          적용 단계 (Markdown)
          <textarea value={steps} onChange={(e) => setSteps(e.target.value)} rows={4} />
        </label>
        <label>
          검증 (Markdown)
          <textarea value={verification} onChange={(e) => setVerification(e.target.value)} rows={3} />
        </label>
        <label>
          트레이드오프 (Markdown)
          <textarea value={tradeoffs} onChange={(e) => setTradeoffs(e.target.value)} rows={3} />
        </label>
      </SubtypeFormFrame>
    </div>
  )
}
