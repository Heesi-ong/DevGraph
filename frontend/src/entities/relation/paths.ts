import type { AnyNodeType } from '../knowledge-node/types'

// Snippet은 전용 화면이 있고 나머지는 지식 상세 화면을 쓴다(Error/Solution/Project 화면은 Phase 6).
export function nodePath(type: AnyNodeType, id: string) {
  return type === 'SNIPPET' ? `/snippets/${id}` : `/nodes/${id}`
}
