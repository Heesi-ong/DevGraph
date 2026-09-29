import { useEffect, useState, type CSSProperties } from 'react'

interface Token {
  content: string
  style?: CSSProperties
}

// 하이라이트는 편의 기능이다. 너무 큰 코드는 토큰화 비용이 커서 원문 그대로 보여 준다.
const MAX_HIGHLIGHT_CHARS = 100_000

// 설계서 §16.4 / SNP-02: 읽기 화면은 Shiki로 토큰화하되 **HTML 문자열을 주입하지 않는다**.
// 토큰을 React 요소로 그려 코드가 어떤 마크업이어도 텍스트로만 표시되고 실행되지 않는다.
export function CodeBlock({ code, language }: { code: string; language: string }) {
  const [lines, setLines] = useState<Token[][] | null>(null)

  useEffect(() => {
    let cancelled = false
    setLines(null)
    if (code.length > MAX_HIGHLIGHT_CHARS) return
    import('shiki')
      .then(async ({ bundledLanguages, codeToTokens }) => {
        if (!(language in bundledLanguages)) return null
        const result = await codeToTokens(code, {
          lang: language as keyof typeof bundledLanguages,
          themes: { light: 'github-light', dark: 'github-dark' },
          defaultColor: false,
        })
        return result.tokens.map((line) =>
          line.map((token) => ({ content: token.content, style: token.htmlStyle as CSSProperties })),
        )
      })
      .then((tokens) => {
        if (!cancelled && tokens) setLines(tokens)
      })
      .catch(() => {
        // 언어 로딩 실패 시 원문 표시로 남는다.
      })
    return () => {
      cancelled = true
    }
  }, [code, language])

  return (
    <pre className="code-block" tabIndex={0} aria-label={`${language} 코드`}>
      <code data-language={language}>
        {lines
          ? lines.map((line, i) => (
              <span key={i} className="code-line">
                {line.map((token, j) => (
                  <span key={j} style={token.style}>
                    {token.content}
                  </span>
                ))}
                {'\n'}
              </span>
            ))
          : code}
      </code>
    </pre>
  )
}
