import { createBrowserRouter } from 'react-router-dom'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { HealthPage } from '../pages/HealthPage'
import { ProtectedRoute } from './ProtectedRoute'

// pages/는 route composition만 담당한다(설계서 §16.1). 화면 구현은 features/widgets가 갖는다.
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/signup',
    element: <SignupPage />,
  },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <HealthPage />
      </ProtectedRoute>
    ),
  },
])
