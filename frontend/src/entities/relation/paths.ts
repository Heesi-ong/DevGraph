import type { AnyNodeType } from '../knowledge-node/types'

// 타입마다 전용 상세 화면이 있다. Concept/Note만 공통 지식 상세(/nodes)를 쓴다.
const PREFIX: Record<AnyNodeType, string> = {
  CONCEPT: 'nodes', NOTE: 'nodes', SNIPPET: 'snippets', ERROR: 'errors', SOLUTION: 'solutions', PROJECT: 'projects', RESOURCE: 'resources',
}

export function nodePath(type: AnyNodeType, id: string) {
  return `/${PREFIX[type]}/${id}`
}
