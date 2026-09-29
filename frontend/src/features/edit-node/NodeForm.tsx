import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { createNode, getNode, updateNode } from '../../entities/knowledge-node/api'
import { nodeKeys } from '../../entities/knowledge-node/queryKeys'
import type { NodeDetail, NodeType } from '../../entities/knowledge-node/types'
import type { Tag } from '../../entities/tag/types'
import { toApiError, type ApiErrorInfo } from '../../shared/api/errors'
import { MarkdownView } from '../../widgets/MarkdownView'
import { TagPicker } from '../tags/TagPicker'
import { useUnsavedChangesWarning } from './useUnsavedChangesWarning'

interface Props {
  mode: 'create' | 'edit'
  initial?: NodeDetail
  initialType?: NodeType
  onSaved: (node: NodeDetail) => void
  onCancel: () => void
}

const FIELD_LABELS: Record<string, string> = {
  title: '제목',
  summary: '요약',
  bodyMd: '본문',
  type: '유형',
  tagIds: '태그',
}

export function NodeForm({ mode, initial, initialType = 'CONCEPT', onSaved, onCancel }: Props) {
  const queryClient = useQueryClient()
  const [type, setType] = useState<NodeType>(initial?.type ?? initialType)
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [bodyMd, setBodyMd] = useState(initial?.bodyMd ?? '')
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])
  const [version, setVersion] = useState(initial?.version ?? 0)
  const [preview, setPreview] = useState(false)
  const [error, setError] = useState<ApiErrorInfo | null>(null)
  // §10.2 409 흐름: 내 입력은 화면에 그대로 두고, 서버의 최신 내용을 옆에 보여 준다.
  const [conflict, setConflict] = useState<NodeDetail | null>(null)

  const baseline = useRef({
    title: initial?.title ?? '',
    summary: initial?.summary ?? '',
    bodyMd: initial?.bodyMd ?? '',
    tagIds: (initial?.tags ?? []).map((t) => t.id).sort().join(','),
  })
  const allowLeave = useRef(false)
  const isDirty = () =>
    !allowLeave.current &&
    (title !== baseline.current.title ||
      summary !== baseline.current.summary ||
      bodyMd !== baseline.current.bodyMd ||
      tags.map((t) => t.id).sort().join(',') !== baseline.current.tagIds)
  useUnsavedChangesWarning(isDirty)

  const save = useMutation({
    mutationFn: (versionToSend: number) => {
      const input = { title, summary, bodyMd, tagIds: tags.map((t) => t.id) }
      return mode === 'create' ? createNode({ ...input, type }) : updateNode(initial!.id, versionToSend, input)
    },
    onSuccess: async (node) => {
      allowLeave.current = true
      queryClient.setQueryData(nodeKeys.detail(node.id), node)
      await queryClient.invalidateQueries({ queryKey: nodeKeys.lists() })
      onSaved(node)
    },
    onError: async (err) => {
      const info = toApiError(err)
      if (info.code === 'VERSION_CONFLICT' && initial) {
        try {
          setConflict(await getNode(initial.id))
          setError(null)
          return
        } catch {
          // 최신 내용을 못 가져오면 일반 오류로 알린다.
        }
      }
      setError(info)
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    save.mutate(version)
  }

  function overwriteWithMine() {
    if (!conflict) return
    setVersion(conflict.version)
    setConflict(null)
    save.mutate(conflict.version)
  }

  function replaceWithLatest() {
    if (!conflict) return
    setTitle(conflict.title)
    setSummary(conflict.summary ?? '')
    setBodyMd(conflict.bodyMd ?? '')
    setTags(conflict.tags)
    setVersion(conflict.version)
    baseline.current = {
      title: conflict.title,
      summary: conflict.summary ?? '',
      bodyMd: conflict.bodyMd ?? '',
      tagIds: conflict.tags.map((t) => t.id).sort().join(','),
    }
    setConflict(null)
  }

  const fieldError = (field: string) => error?.fieldErrors.find((f) => f.field === field)

  return (
    <form onSubmit={handleSubmit} className="form">
      {conflict && (
        <section role="alert" className="conflict">
          <h2>다른 곳에서 먼저 수정되었습니다</h2>
          <p>입력한 내용은 그대로 유지되어 있습니다. 아래 최신 내용을 확인하고 선택해 주세요.</p>
          <dl>
            <dt>최신 제목</dt>
            <dd>{conflict.title}</dd>
            <dt>최신 요약</dt>
            <dd>{conflict.summary || '(없음)'}</dd>
            <dt>최신 본문</dt>
            <dd className="pre">{conflict.bodyMd || '(없음)'}</dd>
          </dl>
          <button type="button" onClick={overwriteWithMine}>
            내 입력으로 덮어쓰기
          </button>{' '}
          <button type="button" onClick={replaceWithLatest}>
            최신 내용으로 교체
          </button>
        </section>
      )}

      {mode === 'create' && (
        <label>
          유형
          <select value={type} onChange={(e) => setType(e.target.value as NodeType)}>
            <option value="CONCEPT">Concept</option>
            <option value="NOTE">Note</option>
          </select>
        </label>
      )}

      <label>
        제목
        <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </label>
      {fieldError('title') && <p role="alert" className="error-text">제목은 1~200자여야 합니다.</p>}

      <label>
        요약
        <textarea value={summary} onChange={(e) => setSummary(e.target.value)} rows={2} maxLength={1000} />
      </label>

      <div>
        <div className="row">
          <label htmlFor="body-input">본문 (Markdown)</label>
          <button type="button" onClick={() => setPreview((p) => !p)} aria-pressed={preview}>
            {preview ? '편집' : '미리보기'}
          </button>
        </div>
        {preview ? (
          <MarkdownView source={bodyMd || '_내용이 없습니다._'} />
        ) : (
          <textarea id="body-input" value={bodyMd} onChange={(e) => setBodyMd(e.target.value)} rows={12} />
        )}
      </div>
      {error?.code === 'PAYLOAD_TOO_LARGE' && <p role="alert" className="error-text">본문은 1MB를 넘길 수 없습니다.</p>}

      <TagPicker selected={tags} onChange={setTags} />

      {error && error.code !== 'PAYLOAD_TOO_LARGE' && (
        <div role="alert" className="error-text">
          <p>{error.message}</p>
          {error.fieldErrors.map((f) => (
            <p key={f.field}>
              {FIELD_LABELS[f.field] ?? f.field}: {f.reason}
            </p>
          ))}
          {error.traceId && <p className="muted">추적 ID: {error.traceId}</p>}
        </div>
      )}

      <div className="row">
        <button type="submit" disabled={save.isPending || !title.trim()}>
          {save.isPending ? '저장 중...' : '저장'}
        </button>
        <button type="button" onClick={onCancel}>
          취소
        </button>
      </div>
    </form>
  )
}
