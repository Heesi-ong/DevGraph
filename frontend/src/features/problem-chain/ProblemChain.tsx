import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { Side } from '../../entities/relation/types'
import type { NodeRelations, RelationLink } from '../../entities/relation/types'
import { nodePath } from '../../entities/relation/paths'
import { RelationPicker } from '../relations/RelationPicker'

interface Step {
  key: string // 관계 타입 key
  side: Side // 이 Node 기준 방향
  title: string
  hint: string
  // 이 단계를 채우는 "다른 쪽" Node 타입 설명
  empty: string
}

// 설계서 §9.7 권장 체인. 비어 있는 단계는 막지 않고 안내만 한다(chain validation warning).
const STEPS: Record<'ERROR' | 'SOLUTION', Step[]> = {
  ERROR: [
    { key: 'CAUSED_BY', side: 'OUTGOING', title: '원인', hint: '이 오류가 왜 생겼는지 설명하는 Concept', empty: '원인 Concept을 연결해 두면 같은 원인의 다른 오류를 찾기 쉬워요.' },
    { key: 'SOLVED_BY', side: 'OUTGOING', title: '해결', hint: '이 오류를 해결한 방법(Solution)', empty: '아직 연결된 해결 방법이 없습니다.' },
    { key: 'OCCURRED_IN', side: 'OUTGOING', title: '발생 프로젝트', hint: '이 오류가 발생한 프로젝트', empty: '어느 프로젝트에서 발생했는지 기록해 두면 프로젝트 회고에 도움이 돼요.' },
  ],
  SOLUTION: [
    { key: 'SOLVED_BY', side: 'INCOMING', title: '해결하는 오류', hint: '이 방법이 해결하는 Error', empty: '아직 이 방법이 해결하는 오류가 연결되지 않았습니다.' },
    { key: 'IMPLEMENTED_WITH', side: 'OUTGOING', title: '구현 코드', hint: '실제로 쓴 Snippet', empty: '실제로 쓴 코드를 Snippet으로 연결해 두면 다시 쓰기 쉬워요.' },
    { key: 'APPLIED_IN', side: 'OUTGOING', title: '적용 프로젝트', hint: '실제로 적용한 프로젝트', empty: '아직 적용한 프로젝트가 없습니다.' },
    { key: 'REFERENCES', side: 'OUTGOING', title: '참고 자료', hint: '참고한 문서·링크(Resource)', empty: '참고한 자료가 있으면 연결해 두세요.' },
  ],
}

function linksFor(relations: NodeRelations, step: Step): RelationLink[] {
  return (step.side === 'OUTGOING' ? relations.outgoing : relations.incoming).filter((l) => l.type === step.key)
}

// 설계서 §19 chain builder: 원인 → 해결 → 코드 → 프로젝트 사슬을 한눈에 보고, 비어 있는 단계를 바로 채운다.
export function ProblemChain({
  nodeId,
  nodeType,
  relations,
  editable,
}: {
  nodeId: string
  nodeType: 'ERROR' | 'SOLUTION'
  relations: NodeRelations
  editable: boolean
}) {
  const [open, setOpen] = useState<string | null>(null)

  return (
    <section aria-label="문제 해결 체인">
      <h2>문제 해결 체인</h2>
      <ol className="chain">
        {STEPS[nodeType].map((step) => {
          const links = linksFor(relations, step)
          const filled = links.length > 0
          return (
            <li key={step.key} className={filled ? 'chain-step chain-step--done' : 'chain-step'}>
              <div className="row">
                <span aria-hidden="true">{filled ? '✓' : '○'}</span>
                <strong>{step.title}</strong>
                <span className="muted small">{step.hint}</span>
                {editable && (
                  <button type="button" aria-expanded={open === step.key} onClick={() => setOpen(open === step.key ? null : step.key)}>
                    {filled ? `${step.title} 더 연결` : `${step.title} 연결`}
                  </button>
                )}
              </div>
              {filled ? (
                <ul className="chain-links" aria-label={step.title}>
                  {links.map((link) => (
                    <li key={link.id}>
                      <Link to={nodePath(link.nodeType, link.nodeId)}>{link.nodeTitle}</Link>{' '}
                      <span className="muted small">{link.nodeType}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted small">{step.empty}</p>
              )}
              {open === step.key && (
                <RelationPicker nodeId={nodeId} nodeType={nodeType} presetTypeKey={step.key} presetSide={step.side} />
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
