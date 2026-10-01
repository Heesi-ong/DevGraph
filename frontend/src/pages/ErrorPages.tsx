import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { changeErrorStatus, createError, getError, updateError } from '../entities/problem/api'
import { problemKeys } from '../entities/problem/queryKeys'
import {
  NEXT_RESOLUTION,
  RESOLUTION_LABEL,
  type ErrorDetail,
  type ResolutionStatus,
} from '../entities/problem/types'
import type { Tag } from '../entities/tag/types'
import { SubtypeFormFrame } from '../features/edit-subtype/SubtypeFormFrame'
import { StatusActions } from '../features/node-status/StatusActions'
import { ProblemChain } from '../features/problem-chain/ProblemChain'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { useInvalidateRelationViews } from '../features/relations/useInvalidateRelationViews'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { toApiError } from '../shared/api/errors'
import { formatDateTime, isoToLocalInput, localInputToIso } from '../shared/format/datetime'
import { ErrorState, LoadingState } from '../shared/ui/StateViews'
import { MarkdownView } from '../widgets/MarkdownView'

function StatusControl({ error }: { error: ErrorDetail }) {
  const invalidate = useInvalidateRelationViews()
  const current = error.error.resolutionStatus
  const [target, setTarget] = useState<ResolutionStatus>(NEXT_RESOLUTION[current][0])
  const [message, setMessage] = useState<string | null>(null)

  const change = useMutation({
    mutationFn: () => changeErrorStatus(error.id, error.version, target),
    onSuccess: async () => {
      setMessage(null)
      await invalidate()
    },
    onError: async (err) => {
      const info = toApiError(err)
      setMessage(
        info.code === 'VERSION_CONFLICT'
          ? '다른 곳에서 수정되어 최신 내용을 다시 불러왔습니다. 다시 시도해 주세요.'
          : info.code === 'INVALID_NODE_STATE'
            ? '휴지통에 있는 항목은 상태를 바꿀 수 없습니다.'
            : info.message,
      )
      await invalidate()
    },
  })

  return (
    <div className="row" role="group" aria-label="해결 상태">
      <span className="badge" data-testid="resolution-status">
        {RESOLUTION_LABEL[current]}
      </span>
      {error.status !== 'TRASHED' && (
        <>
          <select aria-label="바꿀 상태" value={target} onChange={(e) => setTarget(e.target.value as ResolutionStatus)}>
            {NEXT_RESOLUTION[current].map((s) => (
              <option key={s} value={s}>
                {RESOLUTION_LABEL[s]}
              </option>
            ))}
          </select>
          <button type="button" disabled={change.isPending} onClick={() => change.mutate()}>
            상태 변경
          </button>
        </>
      )}
      {message && (
        <span role="alert" className="error-text">
          {message}
        </span>
      )}
    </div>
  )
}

export function ErrorDetailPage() {
  const { id = '' } = useParams()
  const { data: error, isLoading, error: loadError, refetch } = useQuery({
    queryKey: problemKeys.error(id),
    queryFn: () => getError(id),
  })

  if (isLoading) return <LoadingState />
  if (loadError || !error) return <ErrorState error={loadError} onRetry={() => refetch()} />

  const editable = error.status !== 'TRASHED'
  return (
    <article>
      <div className="row">
        <span className="badge">ERROR</span>
        <h1>{error.title}</h1>
        <FavoriteButton nodeId={error.id} favorite={error.favorite} />
      </div>
      {error.status !== 'ACTIVE' && <p className="muted">상태: {error.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      {error.summary && <p className="muted">{error.summary}</p>}
      <StatusControl error={error} />
      {error.warnings.includes('NO_SOLUTION_LINKED') && (
        <p role="status" className="warning-banner">
          해결됨으로 표시되어 있지만 연결된 Solution이 없습니다. 해결 방법을 남겨 두면 같은 오류를 다시 만났을 때 바로 찾을 수 있어요.{' '}
          <Link to={`/solutions/new?errorId=${error.id}`}>해결 방법 추가</Link>
        </p>
      )}

      <h2>오류 메시지</h2>
      <pre className="pre-block">{error.error.errorMessage}</pre>
      {error.error.environment && (
        <p>
          <strong>환경</strong> {error.error.environment}
        </p>
      )}
      <p className="muted small">
        발생 {formatDateTime(error.error.occurredAt)}
        {error.error.resolvedAt && ` · 해결 ${formatDateTime(error.error.resolvedAt)}`}
      </p>
      {error.error.reproductionStepsMd && (
        <>
          <h2>재현 단계</h2>
          <MarkdownView source={error.error.reproductionStepsMd} />
        </>
      )}
      {error.error.causeHypothesisMd && (
        <>
          <h2>원인 가설</h2>
          <MarkdownView source={error.error.causeHypothesisMd} />
        </>
      )}
      {error.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {error.tags.map((tag) => (
            <li key={tag.id} className="chip">
              {tag.name}
            </li>
          ))}
        </ul>
      )}

      <ProblemChain nodeId={error.id} nodeType="ERROR" relations={error.relations} editable={editable} />
      <div className="row">
        <Link to={`/solutions/new?errorId=${error.id}`}>해결 방법 추가</Link>
        {editable && <Link to={`/errors/${error.id}/edit`}>편집</Link>}
        <StatusActions node={error} />
      </div>
      <RelatedNodes nodeId={error.id} nodeType="ERROR" relations={error.relations} editable={editable} showPicker={false} />
    </article>
  )
}

export function ErrorFormPage() {
  const { id } = useParams()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: problemKeys.error(id ?? ''),
    queryFn: () => getError(id!),
    enabled: !!id,
  })
  if (id && isLoading) return <LoadingState />
  if (id && (error || !data)) return <ErrorState error={error} onRetry={() => refetch()} />
  return <ErrorForm initial={data} />
}

function ErrorForm({ initial }: { initial?: ErrorDetail }) {
  const navigate = useNavigate()
  const invalidate = useInvalidateRelationViews()
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [errorMessage, setErrorMessage] = useState(initial?.error.errorMessage ?? '')
  const [environment, setEnvironment] = useState(initial?.error.environment ?? '')
  const [repro, setRepro] = useState(initial?.error.reproductionStepsMd ?? '')
  const [cause, setCause] = useState(initial?.error.causeHypothesisMd ?? '')
  const [occurredAt, setOccurredAt] = useState(isoToLocalInput(initial?.error.occurredAt))
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])

  const dirty = initial
    ? title !== initial.title || summary !== (initial.summary ?? '') || errorMessage !== initial.error.errorMessage ||
      environment !== (initial.error.environment ?? '') || repro !== (initial.error.reproductionStepsMd ?? '') ||
      cause !== (initial.error.causeHypothesisMd ?? '') || tags.map((t) => t.id).join() !== initial.tags.map((t) => t.id).join()
    : !!(title || summary || errorMessage || environment || repro || cause)

  return (
    <div>
      <h1>{initial ? 'Error 편집' : '새 Error'}</h1>
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
            title, summary, errorMessage, environment, reproductionStepsMd: repro, causeHypothesisMd: cause,
            occurredAt: localInputToIso(occurredAt), tagIds: tags.map((t) => t.id),
          }
          const saved = initial ? await updateError(initial.id, initial.version, input) : await createError(input)
          await invalidate()
          return saved
        }}
        onSaved={(saved) => navigate(`/errors/${saved.id}`)}
        onCancel={() => navigate(-1)}
      >
        <label>
          오류 메시지
          <textarea
            value={errorMessage}
            onChange={(e) => setErrorMessage(e.target.value)}
            rows={5}
            required
            className="mono"
            placeholder="예외 메시지나 스택 트레이스를 그대로 붙여 넣으세요"
          />
        </label>
        <label>
          환경
          <input value={environment} onChange={(e) => setEnvironment(e.target.value)} maxLength={500} placeholder="예: Spring Boot 3.3, JDK 21" />
        </label>
        <label>
          발생 시각
          <input type="datetime-local" value={occurredAt} onChange={(e) => setOccurredAt(e.target.value)} />
        </label>
        <label>
          재현 단계 (Markdown)
          <textarea value={repro} onChange={(e) => setRepro(e.target.value)} rows={4} />
        </label>
        <label>
          원인 가설 (Markdown)
          <textarea value={cause} onChange={(e) => setCause(e.target.value)} rows={3} />
        </label>
      </SubtypeFormFrame>
    </div>
  )
}
