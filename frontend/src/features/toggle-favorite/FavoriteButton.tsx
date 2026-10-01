import { useMutation } from '@tanstack/react-query'
import { setFavorite } from '../../entities/knowledge-node/api'
import { useInvalidateRelationViews } from '../relations/useInvalidateRelationViews'

// 설계서 §9.2 KNOW-07. 즐겨찾기는 사용자별 상태라 Node 자체(version)를 바꾸지 않는다.
export function FavoriteButton({ nodeId, favorite }: { nodeId: string; favorite: boolean }) {
  const invalidate = useInvalidateRelationViews()
  const toggle = useMutation({
    mutationFn: () => setFavorite(nodeId, !favorite),
    onSuccess: () => invalidate(),
  })

  return (
    <button
      type="button"
      onClick={() => toggle.mutate()}
      disabled={toggle.isPending}
      aria-pressed={favorite}
      aria-label={favorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
      title={favorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
    >
      {favorite ? '★' : '☆'}
    </button>
  )
}
