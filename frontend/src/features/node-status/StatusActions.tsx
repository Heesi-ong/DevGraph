import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { transitionNode, type NodeTransition } from '../../entities/knowledge-node/api'
import type { NodeStatus } from '../../entities/knowledge-node/types'
import { useInvalidateRelationViews } from '../relations/useInvalidateRelationViews'
import { toApiError } from '../../shared/api/errors'

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
      </div>
      {message && (
        <p role="alert" className="error-text">
          {message}
        </p>
      )}
    </div>
  )
}
