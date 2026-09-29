import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { recordCopy } from '../../entities/snippet/api'
import { snippetKeys } from '../../entities/snippet/queryKeys'

interface Props {
  code: string
  // 현재 버전을 복사할 때만 넘긴다. 이전 버전 원문 복사는 사용 통계에 넣지 않는다.
  snippetId?: string
  label?: string
}

type Result = 'idle' | 'copied' | 'failed'

// SNP-03: 화면에 그려진 텍스트가 아니라 **저장된 원문 문자열**을 그대로 클립보드에 쓴다.
export function CopyCodeButton({ code, snippetId, label = '코드 복사' }: Props) {
  const [result, setResult] = useState<Result>('idle')
  const queryClient = useQueryClient()

  const usage = useMutation({
    mutationFn: () => recordCopy(snippetId!),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: snippetKeys.detail(snippetId!) }),
    onError: () => {
      // 통계 실패는 사용자에게 알리지 않는다(§14.4: 복사를 실패시키지 않는다).
    },
  })

  async function copy() {
    try {
      await navigator.clipboard.writeText(code)
      setResult('copied')
      if (snippetId) usage.mutate()
    } catch {
      setResult('failed')
    }
  }

  return (
    <span className="row">
      <button type="button" onClick={copy}>
        {label}
      </button>
      <span role="status" className={result === 'failed' ? 'error-text' : 'muted small'}>
        {result === 'copied' && '복사했습니다'}
        {result === 'failed' && '복사하지 못했습니다. 코드를 직접 선택해 복사해 주세요.'}
      </span>
    </span>
  )
}
