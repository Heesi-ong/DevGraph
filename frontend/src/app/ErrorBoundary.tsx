import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

// 설계서 §16.1 app shell 구성 요소. 개별 화면 오류가 전체 앱을 하얀 화면으로 만들지 않게 한다.
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled UI error', error, info)
  }

  render() {
    if (this.state.error) {
      return (
        <div role="alert" style={{ padding: 'var(--space-4)' }}>
          <h1>문제가 발생했습니다</h1>
          <p style={{ color: 'var(--color-muted)' }}>{this.state.error.message}</p>
        </div>
      )
    }
    return this.props.children
  }
}
