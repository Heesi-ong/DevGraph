import type { ReactNode } from 'react'
import { toApiError } from '../api/errors'

// 설계서 §10.2 화면 상태별 동작을 한곳에서 통일한다. 화면마다 다르게 구현하지 않는다.

export function LoadingState({ label = '불러오는 중...' }: { label?: string }) {
  return (
    <p role="status" className="state-view">
      {label}
    </p>
  )
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="state-view">
      <h2>{title}</h2>
      {description && <p className="muted">{description}</p>}
      {action}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const info = toApiError(error)
  // §10.2: 403/404는 리소스 존재 여부를 암시하지 않고 안전한 다음 행동만 안내한다.
  const message =
    info.status === 404
      ? '삭제되었거나 이동된 항목일 수 있습니다.'
      : info.status === 403
        ? '접근 권한이 없습니다.'
        : info.message
  return (
    <div role="alert" className="state-view">
      <h2>문제가 발생했습니다</h2>
      <p>{message}</p>
      {info.traceId && <p className="muted">문의 시 추적 ID: {info.traceId}</p>}
      {onRetry && (
        <button type="button" onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  )
}
