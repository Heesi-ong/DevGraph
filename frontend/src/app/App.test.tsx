import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('renders without crashing and shows a loading state while auth bootstraps', () => {
    // useAuthBootstrap의 me() 호출이 끝나기 전(백엔드 없는 테스트 환경에서는 실패로 끝난다)
    // ProtectedRoute가 보여주는 초기 로딩 상태만 동기적으로 검증한다. 로그인/health 화면은
    // 실제 API를 붙이는 통합/e2e 테스트에서 확인한다(playwright, §20.1).
    render(<App />)
    expect(screen.getByText('불러오는 중...')).toBeInTheDocument()
  })
})
