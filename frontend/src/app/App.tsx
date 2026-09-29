import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import { useAuthBootstrap } from '../features/auth/useAuthBootstrap'
import { ErrorBoundary } from './ErrorBoundary'
import { router } from './router'

const queryClient = new QueryClient()

function AuthBootstrap() {
  useAuthBootstrap()
  return <RouterProvider router={router} />
}

export function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthBootstrap />
      </QueryClientProvider>
    </ErrorBoundary>
  )
}
