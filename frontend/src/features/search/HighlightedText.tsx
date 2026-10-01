import type { Segment } from '../../entities/search/types'

// 서버가 준 구간 배열을 React 요소로 그린다. text는 원문 그대로이고 HTML로 해석될 경로가 없다 —
// dangerouslySetInnerHTML도 sanitizer도 필요 없다(§14.7).
export function HighlightedText({ segments }: { segments: Segment[] }) {
  return (
    <>
      {segments.map((segment, i) =>
        segment.matched ? (
          <mark key={i} className="search-mark">
            {segment.text}
          </mark>
        ) : (
          <span key={i}>{segment.text}</span>
        ),
      )}
    </>
  )
}
