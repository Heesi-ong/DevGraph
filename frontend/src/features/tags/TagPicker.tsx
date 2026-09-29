import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { createTag, searchTags } from '../../entities/tag/api'
import { tagKeys } from '../../entities/knowledge-node/queryKeys'
import type { Tag } from '../../entities/tag/types'
import { toApiError } from '../../shared/api/errors'

interface Props {
  selected: Tag[]
  onChange: (tags: Tag[]) => void
}

// 설계서 §9.2 KNOW-06: 태그 자동완성. 입력한 이름이 없으면 즉시 새 태그로 만들 수 있다.
export function TagPicker({ selected, onChange }: Props) {
  const [input, setInput] = useState('')
  const [error, setError] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const query = input.trim()

  const { data: suggestions = [] } = useQuery({
    queryKey: tagKeys.search(query),
    queryFn: () => searchTags(query),
    enabled: query.length > 0,
  })

  const selectedIds = new Set(selected.map((tag) => tag.id))
  const options = suggestions.filter((tag) => !selectedIds.has(tag.id))
  const exactExists = suggestions.some((tag) => tag.name.toLowerCase() === query.toLowerCase())

  function add(tag: Tag) {
    onChange([...selected, tag])
    setInput('')
    setError(null)
  }

  async function createAndAdd() {
    try {
      const tag = await createTag(query)
      await queryClient.invalidateQueries({ queryKey: tagKeys.all })
      add(tag)
    } catch (err) {
      const info = toApiError(err)
      setError(info.code === 'TAG_EXISTS' ? '이미 있는 태그입니다. 목록에서 선택해 주세요.' : info.message)
    }
  }

  return (
    <div>
      <label htmlFor="tag-input">태그</label>
      <ul className="chips" aria-label="선택한 태그">
        {selected.map((tag) => (
          <li key={tag.id} className="chip">
            {tag.name}
            <button type="button" aria-label={`${tag.name} 태그 제거`} onClick={() => onChange(selected.filter((t) => t.id !== tag.id))}>
              ×
            </button>
          </li>
        ))}
      </ul>
      <input
        id="tag-input"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        placeholder="태그 검색 또는 새로 만들기"
        maxLength={50}
        autoComplete="off"
      />
      {query.length > 0 && (
        <ul className="suggestions" aria-label="태그 제안">
          {options.map((tag) => (
            <li key={tag.id}>
              <button type="button" onClick={() => add(tag)}>
                {tag.name}
              </button>
            </li>
          ))}
          {!exactExists && (
            <li>
              <button type="button" onClick={createAndAdd}>
                "{query}" 새 태그 만들기
              </button>
            </li>
          )}
        </ul>
      )}
      {error && (
        <p role="alert" className="error-text">
          {error}
        </p>
      )}
    </div>
  )
}
