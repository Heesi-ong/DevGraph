import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { permanentDeleteNode, transitionNode, type NodeTransition } from '../../entities/knowledge-node/api'
import type { NodeStatus } from '../../entities/knowledge-node/types'
import { useInvalidateRelationViews } from '../relations/useInvalidateRelationViews'
import { toApiError } from '../../shared/api/errors'
import { ReauthDialog } from '../reauth/ReauthDialog'

interface Action {
  transition: NodeTransition
  label: string
  confirm?: string
}

// 설계서 §11.3 상태 전이. 위험 동작은 Archive → Trash → Permanent Delete 순서다(§10.1).
const ACTIONS: Record<NodeStatus, Action[]> = {
  ACTIVE: [
    { transition: 'archive', label: '보관' },
    { transition: 'trash', label: '휴지통으로', confirm: '휴지통으로 이동합니다. 30일 뒤 영구 삭제될 수 있습니다.' },
  ],
  ARCHIVED: [
    { transition: 'archive/restore', label: '보관 해제' },
    { transition: 'trash', label: '휴지통으로', confirm: '휴지통으로 이동합니다. 30일 뒤 영구 삭제될 수 있습니다.' },
  ],
  // TRASHED → ACTIVE 직접 복구는 없다. 항상 보관함으로 돌아간다.
  TRASHED: [{ transition: 'trash/restore', label: '보관함으로 복구' }],
}

// Concept/Note/Snippet 모두 같은 상태 전이 endpoint(/nodes/{id}/...)를 쓴다.
export function StatusActions({ node }: { node: { id: string; version: number; status: NodeStatus } }) {
  const invalidate = useInvalidateRelationViews()
  const [message, setMessage] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)
  const navigate = useNavigate()

  const run = useMutation({
    mutationFn: (transition: NodeTransition) => transitionNode(node.id, node.version, transition),
    onSuccess: async () => {
      setMessage(null)
      await invalidate()
    },
    onError: async (err) => {
      const info = toApiError(err)
      setMessage(
        info.code === 'VERSION_CONFLICT'
          ? '다른 곳에서 수정되어 최신 상태로 새로 불러왔습니다. 다시 시도해 주세요.'
          : info.code === 'INVALID_NODE_STATE'
            ? '현재 상태에서는 할 수 없는 작업입니다.'
            : info.message,
      )
      await invalidate()
    },
  })

  const purge = useMutation({
    mutationFn: (token: string) => permanentDeleteNode(node.id, token),
    onSuccess: () => {
      // 삭제된 항목 화면을 먼저 떠난 뒤 캐시를 무효화한다. 반대로 하면 이미 없는 항목을 다시 읽다가 404 재시도에 막힌다.
      navigate('/library', { replace: true })
      void invalidate()
    },
    onError: (err) => setMessage(toApiError(err).message),
  })

  return (
    <div>
      <div className="row">
        {ACTIONS[node.status].map((action) => (
          <button
            key={action.transition}
            type="button"
            disabled={run.isPending}
            onClick={() => {
              if (action.confirm && !window.confirm(action.confirm)) return
              run.mutate(action.transition)
            }}
          >
            {action.label}
          </button>
        ))}
        {node.status === 'TRASHED' && (
          <button type="button" className="danger" disabled={purge.isPending} onClick={() => { setMessage(null); setAsking(true) }}>
            영구 삭제
          </button>
        )}
      </div>
      {asking && (
        <ReauthDialog
          purpose="NODE_PERMANENT_DELETE"
          targetId={node.id}
          title="영구 삭제"
          description="이 항목과 연결된 관계, 버전 기록이 모두 삭제되며 되돌릴 수 없습니다. 계속하려면 비밀번호를 입력해 주세요."
          confirmLabel="영구 삭제"
          onCancel={() => setAsking(false)}
          onToken={(token) => {
            setAsking(false)
            purge.mutate(token)
          }}
        />
      )}
      {message && (
        <p role="alert" className="error-text">
          {message}
        </p>
      )}
    </div>
  )
}
