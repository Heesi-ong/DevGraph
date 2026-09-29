import { createBrowserRouter } from 'react-router-dom'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { DashboardPage } from '../pages/DashboardPage'
import { LibraryPage } from '../pages/LibraryPage'
import { NodeDetailPage } from '../pages/NodeDetailPage'
import { NodeCreatePage, NodeEditPage } from '../pages/NodeFormPages'
import { AppShell } from '../widgets/AppShell'
import { ProtectedRoute } from './ProtectedRoute'

// pages/는 route composition만 담당한다(설계서 §16.1). 화면 구현은 features/widgets가 갖는다.
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppShell />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'library', element: <LibraryPage /> },
      { path: 'nodes/new', element: <NodeCreatePage /> },
      { path: 'nodes/:id', element: <NodeDetailPage /> },
      { path: 'nodes/:id/edit', element: <NodeEditPage /> },
    ],
  },
])
