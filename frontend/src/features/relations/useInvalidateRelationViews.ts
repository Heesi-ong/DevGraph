import { useQueryClient } from '@tanstack/react-query'
import { graphKeys } from '../../entities/graph/queryKeys'
import { nodeKeys } from '../../entities/knowledge-node/queryKeys'
import { problemKeys } from '../../entities/problem/queryKeys'
import { projectKeys } from '../../entities/project/queryKeys'
import { resourceKeys } from '../../entities/resource/queryKeys'
import { searchKeys } from '../../entities/search/queryKeys'
import { relationKeys } from '../../entities/relation/queryKeys'
import { snippetKeys } from '../../entities/snippet/queryKeys'

// 관계·상태·즐겨찾기는 Node의 모든 파생 화면(타입별 상세·목록, 그래프, Picker 후보, 검색)에 영향을 준다.
// 어느 화면에서 바꿔도 함께 새로 고친다. 후보를 빼면 같은 검색어의 캐시가 이미 연결한 항목을 계속 보여 준다.
export function useInvalidateRelationViews() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: nodeKeys.all }),
      queryClient.invalidateQueries({ queryKey: snippetKeys.all }),
      queryClient.invalidateQueries({ queryKey: graphKeys.all }),
      queryClient.invalidateQueries({ queryKey: relationKeys.allCandidates }),
      queryClient.invalidateQueries({ queryKey: problemKeys.all }),
      queryClient.invalidateQueries({ queryKey: projectKeys.all }),
      queryClient.invalidateQueries({ queryKey: resourceKeys.all }),
      queryClient.invalidateQueries({ queryKey: searchKeys.all }),
    ])
}
