import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { AnyNodeType } from '../../entities/knowledge-node/types'
import { deleteRelation, listRelationTypes, updateRelation } from '../../entities/relation/api'
import { nodePath } from '../../entities/relation/paths'
import { relationKeys } from '../../entities/relation/queryKeys'
import type { NodeRelations, RelationLink, RelationType } from '../../entities/relation/types'
import { toApiError } from '../../shared/api/errors'
import { RelationPicker } from './RelationPicker'
import { useInvalidateRelationViews } from './useInvalidateRelationViews'

interface RowProps {
  link: RelationLink
  nodeType: AnyNodeType
  direction: 'outgoing' | 'incoming'
  types: RelationType[]
  editable: boolean
  onError: (message: string | null) => void
}

function RelationRow({ link, nodeType, direction, types, editable, onError }: RowProps) {
  const invalidate = useInvalidateRelationViews()
  // 이 관계를 다른 타입으로 바꿀 때도 같은 허용 조합 규칙이 적용된다. 미리 걸러 선택지를 줄인다.
  const alternatives = types.filter((t) => {
    const source = direction === 'outgoing' ? nodeType : link.nodeType
    const target = direction === 'outgoing' ? link.nodeType : nodeType
    return t.allowedSourceTypes.includes(source) && t.allowedTargetTypes.includes(target)
  })

  const change = useMutation({
    mutationFn: (relationTypeId: string) => updateRelation(link.id, { relationTypeId }),
    onSuccess: () => {
      onError(null)
      return invalidate()
    },
    onError: (err) => {
      const info = toApiError(err)
      onError(info.code === 'DUPLICATE_RELATION' ? '이미 같은 관계가 있습니다.' : info.message)
    },
  })
  const remove = useMutation({
    mutationFn: () => deleteRelation(link.id),
    onSuccess: () => {
      onError(null)
      return invalidate()
    },
    onError: (err) => onError(toApiError(err).message),
  })

  return (
    <li className="relation-row">
      <div className="row">
        <span className="badge">{link.label}</span>
        <Link to={nodePath(link.nodeType, link.nodeId)}>{link.nodeTitle}</Link>
        <span className="muted small">{link.nodeType}</span>
        {link.nodeStatus === 'ARCHIVED' && <span className="muted small">(보관됨)</span>}
        {editable && (
          <>
            <select
              aria-label={`${link.nodeTitle} 관계 타입 변경`}
              value={link.relationTypeId}
              disabled={change.isPending}
              onChange={(e) => change.mutate(e.target.value)}
            >
              {alternatives.map((t) => (
                <option key={t.id} value={t.id}>
                  {direction === 'outgoing' ? t.forwardLabel : t.inverseLabel}
                </option>
              ))}
            </select>
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => {
                if (window.confirm(`“${link.nodeTitle}”와의 관계를 삭제할까요?`)) remove.mutate()
              }}
            >
              관계 삭제
            </button>
          </>
        )}
      </div>
      {link.note && <p className="muted small">{link.note}</p>}
    </li>
  )
}

// 설계서 §9.2 KNOW-10, §13.1: 이 Node의 관계(outgoing)와 backlink(incoming, inverse label). backlink는 중복 저장이 아니다.
export function RelatedNodes({
  nodeId,
  nodeType,
  relations,
  editable,
}: {
  nodeId: string
  nodeType: AnyNodeType
  relations: NodeRelations
  editable: boolean
}) {
  const [error, setError] = useState<string | null>(null)
  const types = useQuery({ queryKey: relationKeys.types, queryFn: listRelationTypes, staleTime: Infinity })
  const empty = relations.outgoing.length === 0 && relations.incoming.length === 0

  return (
    <section aria-label="관련 항목">
      <div className="row">
        <h2>관련 항목</h2>
        <Link to={`/graph?focus=${nodeId}`}>그래프로 보기</Link>
      </div>
      {empty && <p className="muted">아직 연결된 항목이 없습니다.</p>}
      {relations.outgoing.length > 0 && (
        <ul className="relation-list" aria-label="연결">
          {relations.outgoing.map((link) => (
            <RelationRow key={link.id} link={link} nodeType={nodeType} direction="outgoing"
              types={types.data ?? []} editable={editable} onError={setError} />
          ))}
        </ul>
      )}
      {relations.incoming.length > 0 && (
        <>
          <h3>이 항목을 가리키는 항목 (Backlinks)</h3>
          <ul className="relation-list" aria-label="백링크">
            {relations.incoming.map((link) => (
              <RelationRow key={link.id} link={link} nodeType={nodeType} direction="incoming"
                types={types.data ?? []} editable={editable} onError={setError} />
            ))}
          </ul>
        </>
      )}
      {relations.truncated && <p className="muted small">관계가 많아 일부만 표시합니다. 그래프에서 필터로 좁혀 보세요.</p>}
      {error && (
        <p role="alert" className="error-text">
          {error}
        </p>
      )}
      {editable && <RelationPicker nodeId={nodeId} nodeType={nodeType} />}
    </section>
  )
}
