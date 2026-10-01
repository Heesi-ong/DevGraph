import { MIN_QUERY_LENGTH } from './types'

// 서버와 같은 규칙: 공백 정리 후 2자 미만이거나 한글 자모(조합 중인 글자)만이면 검색어가 아니다(§9.5).
// 이런 입력은 서버로 보내지 않고 최근 항목을 보여 준다.
export function isSearchable(raw: string): boolean {
  const q = raw.trim().replace(/\s+/g, ' ')
  if ([...q].length < MIN_QUERY_LENGTH) return false
  return ![...q].every((ch) => ch === ' ' || (ch >= 'ㄱ' && ch <= 'ㆎ'))
}
