import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'

// 설계서 §8.1 / §19: 헤더의 전역 검색창. `/` 또는 Ctrl/Cmd+K로 어디서든 포커스한다.
export function GlobalSearchBox() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const onSearchPage = location.pathname === '/search'
  const [value, setValue] = useState(onSearchPage ? (params.get('q') ?? '') : '')
  const input = useRef<HTMLInputElement>(null)

  // 검색 화면에서 URL의 q가 바뀌면(최근 검색어 선택 등) 입력창도 맞춘다.
  const urlQuery = onSearchPage ? (params.get('q') ?? '') : null
  useEffect(() => {
    if (urlQuery !== null) setValue(urlQuery)
  }, [urlQuery])

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const typing = !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)
      const isShortcut = (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey) || ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k')
      if (isShortcut) {
        event.preventDefault()
        input.current?.focus()
        input.current?.select()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  function submit(event: FormEvent) {
    event.preventDefault()
    navigate(`/search?q=${encodeURIComponent(value.trim())}`)
  }

  return (
    <form role="search" onSubmit={submit} className="global-search">
      <input
        ref={input}
        type="search"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder="검색 ( / )"
        aria-label="전역 검색"
        maxLength={200}
      />
    </form>
  )
}
