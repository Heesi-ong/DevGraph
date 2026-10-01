const KEY = 'devgraph.recentSearches'
const MAX = 8

// 최근 검색어는 이 브라우저의 편의 기능이다(다른 기기·Claude와 공유되지 않는다). 저장소를 못 쓰는 환경에서도 화면은 동작해야 한다.
export function loadRecentSearches(): string[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string').slice(0, MAX) : []
  } catch {
    return []
  }
}

export function rememberSearch(query: string) {
  try {
    const next = [query, ...loadRecentSearches().filter((q) => q !== query)].slice(0, MAX)
    localStorage.setItem(KEY, JSON.stringify(next))
  } catch {
    // 저장 실패는 무시한다.
  }
}

export function clearRecentSearches() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // 무시
  }
}
