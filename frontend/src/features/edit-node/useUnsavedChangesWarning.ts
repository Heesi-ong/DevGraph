import { useEffect } from 'react'
import { useBlocker } from 'react-router-dom'

// 설계서 §16.4: 자동 저장이 없으므로 저장하지 않은 변경이 있으면 이탈 전에 경고한다.
// 저장 직후 곧바로 navigate 하는 경우 state 갱신이 반영되기 전에 이탈이 시도되므로, 값이 아니라
// "지금 시점의 판단 함수"를 받아 최신 ref 값을 읽게 한다.
export function useUnsavedChangesWarning(isDirty: () => boolean) {
  const blocker = useBlocker(() => isDirty())

  useEffect(() => {
    if (blocker.state === 'blocked') {
      if (window.confirm('저장하지 않은 변경이 있습니다. 나가시겠습니까?')) {
        blocker.proceed()
      } else {
        blocker.reset()
      }
    }
  }, [blocker])

  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (isDirty()) {
        event.preventDefault()
      }
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [isDirty])
}
