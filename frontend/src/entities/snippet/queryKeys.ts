import type { SnippetFilters } from './types'

// 설계서 §16.2 / §28: 필터를 key에 모두 넣어 캐시가 섞이지 않게 한다.
export const snippetKeys = {
  all: ['snippets'] as const,
  lists: () => ['snippets', 'list'] as const,
  list: (filters: SnippetFilters) => ['snippets', 'list', filters] as const,
  detail: (id: string) => ['snippets', 'detail', id] as const,
  versions: (id: string) => ['snippets', 'versions', id] as const,
  diff: (id: string, from: number, to: number) => ['snippets', 'diff', id, from, to] as const,
  version: (id: string, versionNo: number) => ['snippets', 'version', id, versionNo] as const,
}
