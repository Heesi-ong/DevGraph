import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getFocusGraph, getWorkspaceGraph } from '../entities/graph/api'
import { graphKeys } from '../entities/graph/queryKeys'
import type { GraphFilters, GraphNode, GraphResponse } from '../entities/graph/types'
import type { AnyNodeType } from '../entities/knowledge-node/types'
import { listRelationTypes } from '../entities/relation/api'
import { nodePath } from '../entities/relation/paths'
import { relationKeys } from '../entities/relation/queryKeys'
import { GraphCanvas } from '../features/graph-view/GraphCanvas'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'

const FILTERABLE_TYPES: AnyNodeType[] = ['CONCEPT', 'NOTE', 'SNIPPET', 'ERROR', 'SOLUTION', 'PROJECT', 'RESOURCE']
const TRUNCATION_TEXT: Record<string, string> = {
  MAX_NODES: '항목이 많아 일부만 표시합니다.',
  MAX_EDGES: '관계가 많아 일부만 표시합니다.',
  MAX_DEPTH: '탐색 깊이 제한으로 일부만 표시합니다.',
  QUERY_ROW_CAP: '탐색 대상이 많아 일부만 표시합니다.',
}

// 필터는 URL search params가 유일한 상태다(§16). 새로고침·공유 링크에서도 같은 그래프가 나온다.
function readFilters(params: URLSearchParams): GraphFilters {
  const depth = Number(params.get('depth') ?? 1)
  return {
    focus: params.get('focus') || undefined,
    depth: Number.isInteger(depth) ? Math.min(3, Math.max(1, depth)) : 1,
    nodeTypes: (params.get('nodeTypes')?.split(',').filter(Boolean) ?? []) as AnyNodeType[],
    relationTypes: params.get('relationTypes')?.split(',').filter(Boolean) ?? [],
    archived: params.get('archived') === 'true',
  }
}

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

export function GraphPage() {
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params)
  const [view, setView] = useState<'graph' | 'list'>('graph')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const types = useQuery({ queryKey: relationKeys.types, queryFn: listRelationTypes, staleTime: Infinity })
  const graph = useQuery({
    queryKey: graphKeys.view(filters),
    queryFn: () => (filters.focus ? getFocusGraph({ ...filters, focus: filters.focus }) : getWorkspaceGraph(filters)),
  })

  function update(changes: Record<string, string | null>) {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setParams(next, { replace: true })
    setSelectedId(null)
  }

  const data = graph.data
  const selected = data?.nodes.find((n) => n.id === selectedId) ?? null
  const focusNode = data?.nodes.find((n) => n.id === filters.focus)

  return (
    <div>
      <h1>Graph</h1>
      <p className="muted">
        {filters.focus ? (
          <>
            <strong>{focusNode?.title ?? '선택한 항목'}</strong> 중심 (depth {filters.depth}){' '}
            <button type="button" onClick={() => update({ focus: null })}>
              중심 해제
            </button>
          </>
        ) : (
          '최근 수정한 항목 순으로 표시합니다.'
        )}
      </p>

      <fieldset className="graph-filters">
        <legend>필터</legend>
        <div className="row" role="group" aria-label="항목 유형">
          {FILTERABLE_TYPES.map((type) => (
            <label key={type}>
              <input
                type="checkbox"
                checked={filters.nodeTypes.includes(type)}
                onChange={() => update({ nodeTypes: toggle(filters.nodeTypes, type).join(',') || null })}
              />{' '}
              {type}
            </label>
          ))}
        </div>
        <div className="row" role="group" aria-label="관계 유형">
          {(types.data ?? []).map((t) => (
            <label key={t.key}>
              <input
                type="checkbox"
                checked={filters.relationTypes.includes(t.key)}
                onChange={() => update({ relationTypes: toggle(filters.relationTypes, t.key).join(',') || null })}
              />{' '}
              {t.forwardLabel}
            </label>
          ))}
        </div>
        <div className="row">
          {filters.focus && (
            <label>
              깊이
              <select value={filters.depth} onChange={(e) => update({ depth: e.target.value })}>
                <option value={1}>1</option>
                <option value={2}>2</option>
                <option value={3}>3</option>
              </select>
            </label>
          )}
          <label>
            <input
              type="checkbox"
              checked={filters.archived}
              onChange={(e) => update({ archived: e.target.checked ? 'true' : null })}
            />{' '}
            보관된 항목 포함
          </label>
        </div>
      </fieldset>

      {graph.isLoading && <LoadingState />}
      {graph.error && <ErrorState error={graph.error} onRetry={() => graph.refetch()} />}
      {data && data.truncated && (
        <p role="status" className="truncation">
          {TRUNCATION_TEXT[data.truncationReason] ?? '일부만 표시합니다.'} 필터로 좁히거나 항목을 중심으로 탐색해 보세요.
        </p>
      )}
      {data && !filters.focus && data.cursor && (
        <p role="status" className="muted small">
          최근 수정한 {data.nodes.length}개 항목만 표시합니다. 이 화면에서는 페이지를 넘나드는 관계는 보이지 않습니다.
        </p>
      )}

      {data && data.nodes.length === 0 && (
        <EmptyState title="표시할 항목이 없습니다" description="항목을 만들고 관계로 연결하면 여기에 나타납니다." />
      )}

      {data && data.nodes.length > 0 && (
        <>
          <div className="row" role="tablist" aria-label="보기 방식">
            <button type="button" role="tab" aria-selected={view === 'graph'} onClick={() => setView('graph')}>
              그래프
            </button>
            <button type="button" role="tab" aria-selected={view === 'list'} onClick={() => setView('list')}>
              목록
            </button>
          </div>
          <div className="graph-layout">
            <div className="graph-main">
              {view === 'graph' ? (
                <GraphCanvas
                  key={graphIdentity(filters, data)}
                  graph={data}
                  focusId={filters.focus}
                  onSelect={setSelectedId}
                />
              ) : (
                <GraphList graph={data} onSelect={setSelectedId} selectedId={selectedId} />
              )}
            </div>
            <NodeDrawer
              node={selected}
              graph={data}
              onFocus={(id) => update({ focus: id })}
              onClose={() => setSelectedId(null)}
            />
          </div>
        </>
      )}

      {data && data.nextExpansionCandidates.length > 0 && (
        <section aria-label="확장 후보">
          <h2>더 탐색하기</h2>
          <ul className="candidate-list">
            {data.nextExpansionCandidates.map((c) => (
              <li key={c.nodeId}>
                <button type="button" onClick={() => update({ focus: c.nodeId })}>
                  <span className="badge">{c.type}</span> {c.title}
                  <span className="muted small"> · {c.viaRelation}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

// 같은 요청이라도 응답 내용이 바뀌면(관계 추가·삭제) 다시 배치하도록 key에 id 집합을 넣는다.
function graphIdentity(filters: GraphFilters, data: GraphResponse) {
  return [JSON.stringify(filters), data.nodes.map((n) => n.id).join(','), data.edges.map((e) => e.id).join(',')].join('|')
}

// 설계서 §16.3 / §19 "keyboard graph alternative": 캔버스 없이 같은 정보를 키보드로 탐색할 수 있는 목록.
function GraphList({ graph, onSelect, selectedId }: { graph: GraphResponse; onSelect: (id: string) => void; selectedId: string | null }) {
  const title = (id: string) => graph.nodes.find((n) => n.id === id)?.title ?? id
  return (
    <div>
      <section aria-label="항목 목록">
        <h2>항목 ({graph.nodes.length})</h2>
        <ul className="graph-list">
          {graph.nodes.map((n) => (
            <li key={n.id}>
              <button type="button" aria-pressed={n.id === selectedId} onClick={() => onSelect(n.id)}>
                <span className="badge">{n.type}</span> {n.title}
                {n.status === 'ARCHIVED' && <span className="muted small"> (보관됨)</span>}
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section aria-label="관계 목록">
        <h2>관계 ({graph.edges.length})</h2>
        <ul className="graph-list">
          {graph.edges.map((e) => (
            <li key={e.id}>
              {title(e.source)} <span className="badge">{e.label}</span> {title(e.target)}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

// 노드를 누르면 화면을 이동하지 않고 side drawer로 상세를 보여 준다(§16.3). 이동은 명시적 "상세 보기"로만 한다.
function NodeDrawer({ node, graph, onFocus, onClose }: { node: GraphNode | null; graph: GraphResponse; onFocus: (id: string) => void; onClose: () => void }) {
  if (!node) {
    return (
      <aside className="graph-drawer" aria-label="항목 상세">
        <p className="muted">항목을 선택하면 상세가 여기에 표시됩니다.</p>
      </aside>
    )
  }
  const title = (id: string) => graph.nodes.find((n) => n.id === id)?.title ?? id
  const edges = graph.edges.filter((e) => e.source === node.id || e.target === node.id)
  return (
    <aside className="graph-drawer" aria-label="항목 상세">
      <div className="row">
        <span className="badge">{node.type}</span>
        <h2>{node.title}</h2>
      </div>
      {node.status === 'ARCHIVED' && <p className="muted">보관된 항목입니다.</p>}
      <div className="row">
        <Link to={nodePath(node.type, node.id)}>상세 보기</Link>
        <button type="button" onClick={() => onFocus(node.id)}>
          이 항목 중심으로 보기
        </button>
        <button type="button" onClick={onClose}>
          닫기
        </button>
      </div>
      <h3>연결 ({edges.length})</h3>
      <ul>
        {edges.map((e) => (
          <li key={e.id}>
            {e.source === node.id ? (
              <>
                <span className="badge">{e.label}</span> {title(e.target)}
              </>
            ) : (
              <>
                {title(e.source)} <span className="badge">{e.label}</span> 이 항목
              </>
            )}
          </li>
        ))}
      </ul>
    </aside>
  )
}
