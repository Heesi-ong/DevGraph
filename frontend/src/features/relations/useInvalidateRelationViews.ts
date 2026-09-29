import { useQueryClient } from '@tanstack/react-query'
import { graphKeys } from '../../entities/graph/queryKeys'
import { nodeKeys } from '../../entities/knowledge-node/queryKeys'
import { relationKeys } from '../../entities/relation/queryKeys'
import { snippetKeys } from '../../entities/snippet/queryKeys'

// 관계는 양쪽 Node의 상세, 그래프, Relation Picker 후보("아직 연결되지 않은 것")에 모두 영향을 준다.
// 어느 화면에서 바꿔도 네 범위를 함께 새로 고친다. 후보를 빼면 같은 검색어의 캐시가 이미 연결한 항목을 계속 보여 준다.
export function useInvalidateRelationViews() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: nodeKeys.all }),
      queryClient.invalidateQueries({ queryKey: snippetKeys.all }),
      queryClient.invalidateQueries({ queryKey: graphKeys.all }),
      queryClient.invalidateQueries({ queryKey: relationKeys.allCandidates }),
    ])
}
