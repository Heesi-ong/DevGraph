import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { Tag } from '../../entities/tag/types'
import { toApiError, type ApiErrorInfo } from '../../shared/api/errors'
import { useUnsavedChangesWarning } from '../edit-node/useUnsavedChangesWarning'
import { TagPicker } from '../tags/TagPicker'

interface Props<T> {
  title: string
  onTitle: (value: string) => void
  summary: string
  onSummary: (value: string) => void
  tags: Tag[]
  onTags: (tags: Tag[]) => void
  dirty: boolean
  /** 저장을 수행해 결과를 돌려준다. 실패하면 던진다(프레임이 오류를 보여 준다). */
  save: () => Promise<T>
  onSaved: (saved: T) => void
  onCancel: () => void
  children: ReactNode
}

// Error/Solution/Project/Resource 편집 폼의 공통 틀: 제목·요약·태그·저장·취소·오류 표시·이탈 경고(§16.4).
export function SubtypeFormFrame<T>({
  title,
  onTitle,
  summary,
  onSummary,
  tags,
  onTags,
  dirty,
  save,
  onSaved,
  onCancel,
  children,
}: Props<T>) {
  const [error, setError] = useState<ApiErrorInfo | null>(null)
  const [saving, setSaving] = useState(false)
  const leaving = useRef(false)
  useUnsavedChangesWarning(() => dirty && !leaving.current)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSaving(true)
    try {
      const saved = await save()
      // 저장 직후 이동할 때 이탈 경고가 뜨지 않도록 먼저 표시한다.
      leaving.current = true
      onSaved(saved)
    } catch (err) {
      setError(toApiError(err))
      setSaving(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="form">
      <label>
        제목
        <input value={title} onChange={(e) => onTitle(e.target.value)} required maxLength={200} />
      </label>
      <label>
        요약
        <textarea value={summary} onChange={(e) => onSummary(e.target.value)} rows={2} maxLength={1000} />
      </label>
      {children}
      <TagPicker selected={tags} onChange={onTags} />
      {error && (
        <div role="alert" className="error-text">
          <p>
            {error.code === 'VERSION_CONFLICT'
              ? '다른 곳에서 먼저 수정되었습니다. 새로고침해 최신 내용을 확인한 뒤 다시 시도해 주세요.'
              : error.message}
          </p>
          {error.fieldErrors.length > 0 && (
            <ul>
              {error.fieldErrors.map((f, i) => (
                <li key={i}>
                  {f.field}: {f.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <div className="row">
        <button type="submit" disabled={saving || !title.trim()}>
          {saving ? '저장 중...' : '저장'}
        </button>
        <button type="button" onClick={onCancel}>
          취소
        </button>
      </div>
    </form>
  )
}
