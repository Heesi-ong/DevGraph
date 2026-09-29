import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { listNodes } from '../entities/knowledge-node/api'
import { nodeKeys } from '../entities/knowledge-node/queryKeys'
import { QuickCreate } from '../features/create-node/QuickCreate'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'
import { NodeList } from '../widgets/NodeList'

function Section({ title, section, favoriteOnly }: { title: string; section: 'recent' | 'favorites'; favoriteOnly?: boolean }) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: nodeKeys.dashboard(section),
    queryFn: () => listNodes(favoriteOnly ? { favorite: true } : {}, undefined, 5),
  })

  return (
    <section>
      <h2>{title}</h2>
      {isLoading && <LoadingState />}
      {error && <ErrorState error={error} onRetry={() => refetch()} />}
      {data && data.items.length === 0 && (
        <EmptyState
          title={favoriteOnly ? '즐겨찾기한 항목이 없습니다' : '아직 지식이 없습니다'}
          description={favoriteOnly ? '항목의 ☆를 눌러 자주 보는 지식을 모아 보세요.' : '위 입력창에 제목만 적어도 바로 시작할 수 있어요.'}
        />
      )}
      {data && data.items.length > 0 && <NodeList nodes={data.items} />}
    </section>
  )
}

// 설계서 §8 Dashboard: Quick Create, Recent Items, Favorites. Activity Summary는 조회 API가 정의되는 때 추가한다.
export function DashboardPage() {
  return (
    <div>
      <h1>Dashboard</h1>
      <QuickCreate />
      <Section title="최근 수정한 항목" section="recent" />
      <Section title="즐겨찾기" section="favorites" favoriteOnly />
      <p>
        <Link to="/library">Library에서 모두 보기</Link>
      </p>
    </div>
  )
}
