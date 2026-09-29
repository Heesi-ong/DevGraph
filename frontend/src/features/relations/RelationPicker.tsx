import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { AnyNodeType } from '../../entities/knowledge-node/types'
import { createRelation, findTargetCandidates, listRelationTypes } from '../../entities/relation/api'
import { relationKeys } from '../../entities/relation/queryKeys'
import type { Side } from '../../entities/relation/types'
import { toApiError } from '../../shared/api/errors'
import { useInvalidateRelationViews } from './useInvalidateRelationViews'

function useDebounced<T>(value: T, ms: number) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return debounced
}

// 설계서 §9.4 REL-01, §19 Relation Picker. 타입과 방향을 고르면 서버가 그 조합이 허용하는 미연결 Node만 후보로 준다.
export function RelationPicker({ nodeId, nodeType }: { nodeId: string; nodeType: AnyNodeType }) {
  const [side, setSide] = useState<Side>('OUTGOING')
  const [typeId, setTypeId] = useState('')
  const [q, setQ] = useState('')
  const [note, setNote] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const debouncedQ = useDebounced(q.trim(), 250)
  const invalidate = useInvalidateRelationViews()

  const types = useQuery({ queryKey: relationKeys.types, queryFn: listRelationTypes, staleTime: Infinity })
  // 이 Node의 타입이 그 방향의 Source/Target으로 허용되는 관계 타입만 고를 수 있다.
  const usable = (types.data ?? []).filter((t) =>
    (side === 'OUTGOING' ? t.allowedSourceTypes : t.allowedTargetTypes).includes(nodeType),
  )
  const selected = usable.find((t) => t.id === typeId) ?? usable[0]

  const candidates = useQuery({
    queryKey: relationKeys.candidates(nodeId, selected?.id ?? '', side, debouncedQ),
    queryFn: () => findTargetCandidates({ nodeId, relationTypeId: selected!.id, side, q: debouncedQ || undefined }),
    enabled: !!selected,
  })

  const create = useMutation({
    mutationFn: (candidateId: string) =>
      createRelation({
        sourceNodeId: side === 'OUTGOING' ? nodeId : candidateId,
        targetNodeId: side === 'OUTGOING' ? candidateId : nodeId,
        relationTypeId: selected!.id,
        note: note.trim() || undefined,
      }),
    onSuccess: async () => {
      setNote('')
      setQ('')
      setMessage('연결했습니다')
      await invalidate()
    },
    onError: (err) => {
      const info = toApiError(err)
      setMessage(info.code === 'DUPLICATE_RELATION' ? '이미 연결되어 있습니다.' : info.message)
    },
  })

  if (types.isLoading) return <p role="status" className="muted">관계 타입을 불러오는 중...</p>

  return (
    <section aria-label="관계 추가" className="relation-picker">
      <h3>관계 추가</h3>
      <div className="row">
        <label>
          방향
          <select value={side} onChange={(e) => setSide(e.target.value as Side)}>
            <option value="OUTGOING">이 항목 → 대상</option>
            <option value="INCOMING">대상 → 이 항목</option>
          </select>
        </label>
        <label>
          관계
          <select value={selected?.id ?? ''} onChange={(e) => setTypeId(e.target.value)} disabled={usable.length === 0}>
            {usable.map((t) => (
              <option key={t.id} value={t.id}>
                {side === 'OUTGOING' ? t.forwardLabel : t.inverseLabel}
              </option>
            ))}
          </select>
        </label>
      </div>
      {usable.length === 0 && <p className="muted">이 방향으로 연결할 수 있는 관계가 없습니다.</p>}
      {selected && (
        <>
          <p className="muted small">{selected.description}</p>
          <label>
            대상 검색
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="제목으로 검색" maxLength={100} />
          </label>
          <label>
            메모 (선택)
            <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
          </label>
          {candidates.isLoading && <p role="status" className="muted">후보를 찾는 중...</p>}
          {candidates.data && candidates.data.length === 0 && (
            <p className="muted">연결할 수 있는 항목이 없습니다.</p>
          )}
          <ul className="candidate-list" aria-label="연결 후보">
            {(candidates.data ?? []).map((c) => (
              <li key={c.id}>
                <button type="button" onClick={() => create.mutate(c.id)} disabled={create.isPending}>
                  <span className="badge">{c.type}</span> {c.title}
                  {c.status === 'ARCHIVED' && <span className="muted small"> (보관됨)</span>}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {message && (
        <p role="status" className={create.isError ? 'error-text' : 'muted small'}>
          {message}
        </p>
      )}
    </section>
  )
}
