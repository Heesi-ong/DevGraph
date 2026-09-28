import { useQuery } from '@tanstack/react-query'
import axios from 'axios'

interface HealthResponse {
  status: string
}

// Phase 0 완료 조건 확인용: frontend가 backend health를 호출한다(설계서 19절 Phase 0).
export function HealthPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['backend-health'],
    queryFn: async () => {
      const response = await axios.get<HealthResponse>('/actuator/health')
      return response.data
    },
  })

  return (
    <main style={{ padding: 'var(--space-4)' }}>
      <h1>DevGraph</h1>
      <p>Backend health: {isLoading ? '확인 중...' : isError ? '연결 실패' : data?.status}</p>
    </main>
  )
}
