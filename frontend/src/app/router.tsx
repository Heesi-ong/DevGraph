import { createBrowserRouter } from 'react-router-dom'
import { HealthPage } from '../pages/HealthPage'

// pages/는 route composition만 담당한다(설계서 §16.1). 화면 구현은 features/widgets가 갖는다.
export const router = createBrowserRouter([
  {
    path: '/',
    element: <HealthPage />,
  },
])
