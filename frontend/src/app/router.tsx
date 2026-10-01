import { createBrowserRouter } from 'react-router-dom'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { DashboardPage } from '../pages/DashboardPage'
import { LibraryPage } from '../pages/LibraryPage'
import { NodeDetailPage } from '../pages/NodeDetailPage'
import { NodeCreatePage, NodeEditPage } from '../pages/NodeFormPages'
import { SnippetListPage } from '../pages/SnippetListPage'
import { SnippetDetailPage } from '../pages/SnippetDetailPage'
import { SnippetCreatePage, SnippetEditPage } from '../pages/SnippetFormPages'
import { SearchPage } from '../pages/SearchPage'
import { ProblemsPage } from '../pages/ProblemsPage'
import { ErrorDetailPage, ErrorFormPage } from '../pages/ErrorPages'
import { SolutionDetailPage, SolutionFormPage } from '../pages/SolutionPages'
import { ProjectDetailPage, ProjectFormPage, ProjectListPage } from '../pages/ProjectPages'
import { ResourceDetailPage, ResourceFormPage, ResourceListPage } from '../pages/ResourcePages'
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
      // React Flow는 크다. Graph 화면에 들어갈 때만 내려받는다(§16.3).
      { path: 'graph', lazy: async () => ({ Component: (await import('../pages/GraphPage')).GraphPage }) },
      { path: 'search', element: <SearchPage /> },
      { path: 'problems', element: <ProblemsPage /> },
      { path: 'errors/new', element: <ErrorFormPage /> },
      { path: 'errors/:id', element: <ErrorDetailPage /> },
      { path: 'errors/:id/edit', element: <ErrorFormPage /> },
      { path: 'solutions/new', element: <SolutionFormPage /> },
      { path: 'solutions/:id', element: <SolutionDetailPage /> },
      { path: 'solutions/:id/edit', element: <SolutionFormPage /> },
      { path: 'projects', element: <ProjectListPage /> },
      { path: 'projects/new', element: <ProjectFormPage /> },
      { path: 'projects/:id', element: <ProjectDetailPage /> },
      { path: 'projects/:id/edit', element: <ProjectFormPage /> },
      { path: 'resources', element: <ResourceListPage /> },
      { path: 'resources/new', element: <ResourceFormPage /> },
      { path: 'resources/:id', element: <ResourceDetailPage /> },
      { path: 'resources/:id/edit', element: <ResourceFormPage /> },
      { path: 'snippets', element: <SnippetListPage /> },
      { path: 'snippets/new', element: <SnippetCreatePage /> },
      { path: 'snippets/:id', element: <SnippetDetailPage /> },
      { path: 'snippets/:id/edit', element: <SnippetEditPage /> },
    ],
  },
])
