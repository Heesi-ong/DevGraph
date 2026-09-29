import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { createNode } from '../../entities/knowledge-node/api'
import { nodeKeys } from '../../entities/knowledge-node/queryKeys'
import type { NodeType } from '../../entities/knowledge-node/types'
import { toApiError } from '../../shared/api/errors'

// 설계서 §10.1: 전체 편집 화면과 Quick Create 두 경로는 같은 validation(서버)을 쓴다. 관계는 강제하지 않는다.
export function QuickCreate() {
  const [type, setType] = useState<NodeType>('CONCEPT')
  const [title, setTitle] = useState('')
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const create = useMutation({
    mutationFn: () => createNode({ type, title, summary: '', bodyMd: '', tagIds: [] }),
    onSuccess: async (node) => {
      await queryClient.invalidateQueries({ queryKey: nodeKeys.all })
      navigate(`/nodes/${node.id}`)
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (title.trim()) create.mutate()
  }

  return (
    <form onSubmit={handleSubmit} className="row" aria-label="빠른 생성">
      <select value={type} onChange={(e) => setType(e.target.value as NodeType)} aria-label="유형">
        <option value="CONCEPT">Concept</option>
        <option value="NOTE">Note</option>
      </select>
      <input
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder="예: Spring Security"
        aria-label="제목"
        maxLength={200}
      />
      <button type="submit" disabled={create.isPending || !title.trim()}>
        만들기
      </button>
      {create.isError && (
        <span role="alert" className="error-text">
          {toApiError(create.error).message}
        </span>
      )}
    </form>
  )
}
