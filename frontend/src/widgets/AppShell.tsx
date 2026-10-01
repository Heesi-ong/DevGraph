import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { logout } from '../features/auth/api'
import { useAuthStore } from '../features/auth/authStore'
import { GlobalSearchBox } from '../features/search/GlobalSearchBox'

export function AppShell() {
  const user = useAuthStore((state) => state.user)
  const workspace = useAuthStore((state) => state.workspace)
  const setAnonymous = useAuthStore((state) => state.setAnonymous)
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    setAnonymous()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <header className="shell-header">
        <strong>DevGraph</strong>
        <nav aria-label="주 메뉴" className="row">
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/library">Library</NavLink>
          <NavLink to="/snippets">Snippets</NavLink>
          <NavLink to="/problems">Problems</NavLink>
          <NavLink to="/projects">Projects</NavLink>
          <NavLink to="/resources">Resources</NavLink>
          <NavLink to="/graph">Graph</NavLink>
        </nav>
        <GlobalSearchBox />
        <span className="muted small">
          {user?.displayName} · {workspace?.name ?? '개인 Workspace'}
        </span>
        <button type="button" onClick={handleLogout}>로그아웃</button>
      </header>
      <main className="shell-main">
        <Outlet />
      </main>
    </div>
  )
}
