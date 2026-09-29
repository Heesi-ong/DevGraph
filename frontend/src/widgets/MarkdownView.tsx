import Markdown from 'react-markdown'

// 설계서 §17.3: Markdown은 raw HTML을 렌더링하지 않는다. react-markdown은 기본적으로 HTML 태그를
// 문자로 취급하고 javascript: 같은 위험한 URL을 걸러낸다 — rehype-raw 같은 플러그인을 추가하지 말 것.
export function MarkdownView({ source }: { source: string }) {
  return (
    <div className="markdown">
      <Markdown>{source}</Markdown>
    </div>
  )
}
