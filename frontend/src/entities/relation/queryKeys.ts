import type { Side } from './types'

export const relationKeys = {
  types: ['relation-types'] as const,
  allCandidates: ['relation-candidates'] as const,
  candidates: (nodeId: string, typeId: string, side: Side, q: string) =>
    ['relation-candidates', nodeId, typeId, side, q] as const,
}
