import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { createProject, getProject, listProjects, updateProject } from '../entities/project/api'
import { projectKeys } from '../entities/project/queryKeys'
import {
  PROJECT_STATUS_LABEL,
  type ProjectDetail,
  type ProjectFilters,
  type ProjectStatus,
} from '../entities/project/types'
import { nodePath } from '../entities/relation/paths'
import type { RelationLink } from '../entities/relation/types'
import type { Tag } from '../entities/tag/types'
import { SubtypeFormFrame } from '../features/edit-subtype/SubtypeFormFrame'
import { StatusActions } from '../features/node-status/StatusActions'
import { RelatedNodes } from '../features/relations/RelatedNodes'
import { useInvalidateRelationViews } from '../features/relations/useInvalidateRelationViews'
import { FavoriteButton } from '../features/toggle-favorite/FavoriteButton'
import { EmptyState, ErrorState, LoadingState } from '../shared/ui/StateViews'
import { MarkdownView } from '../widgets/MarkdownView'

const ProjectGraphTab = lazy(() => import('./ProjectGraphTab'))
const STATUSES = Object.keys(PROJECT_STATUS_LABEL) as ProjectStatus[]

export function ProjectListPage() {
  const [params, setParams] = useSearchParams()
  const projectStatus = (params.get('projectStatus')?.split(',').filter(Boolean) ?? []) as ProjectStatus[]
  const filters: ProjectFilters = { projectStatus }
  const q = useInfiniteQuery({
    queryKey: projectKeys.list(filters),
    queryFn: ({ pageParam }) => listProjects(filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? (last.cursor ?? undefined) : undefined),
  })
  const items = q.data?.pages.flatMap((p) => p.items) ?? []

  function toggle(status: ProjectStatus) {
    const next = new URLSearchParams(params)
    const list = projectStatus.includes(status) ? projectStatus.filter((s) => s !== status) : [...projectStatus, status]
    if (list.length) next.set('projectStatus', list.join(','))
    else next.delete('projectStatus')
    setParams(next, { replace: true })
  }

  return (
    <div>
      <div className="row">
        <h1>Projects</h1>
        <Link to="/projects/new">새 Project</Link>
      </div>
      <div className="row" role="group" aria-label="상태 필터">
        {STATUSES.map((s) => (
          <label key={s}>
            <input type="checkbox" checked={projectStatus.includes(s)} onChange={() => toggle(s)} /> {PROJECT_STATUS_LABEL[s]}
          </label>
        ))}
      </div>
      {q.isLoading && <LoadingState />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && items.length === 0 && (
        <EmptyState
          title="조건에 맞는 Project가 없습니다"
          description="실제로 만든 프로젝트를 등록하면 기술 지식과 겪은 문제를 한곳에서 돌아볼 수 있어요."
          action={<Link to="/projects/new">첫 Project 만들기</Link>}
        />
      )}
      {items.length > 0 && (
        <ul className="node-list" aria-label="Project 목록">
          {items.map((p) => (
            <li key={p.id} className="node-card">
              <div className="row">
                <span className="badge">{PROJECT_STATUS_LABEL[p.projectStatus]}</span>
                <Link to={`/projects/${p.id}`} className="node-title">
                  {p.title}
                </Link>
                <FavoriteButton nodeId={p.id} favorite={p.favorite} />
              </div>
              {p.summary && <p className="muted">{p.summary}</p>}
              <p className="muted small">
                지식 {p.knowledgeCount} · 문제 {p.problemCount} · 해결 {p.solutionCount}
                {p.startedOn && ` · ${p.startedOn}${p.endedOn ? ` ~ ${p.endedOn}` : ' ~'}`}
              </p>
            </li>
          ))}
        </ul>
      )}
      {q.hasNextPage && (
        <button type="button" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
          더 보기
        </button>
      )}
    </div>
  )
}

type Tab = 'overview' | 'knowledge' | 'problems' | 'snippets' | 'graph'
const TABS: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'knowledge', label: 'Connected Knowledge' },
  { key: 'problems', label: 'Problems' },
  { key: 'snippets', label: 'Snippets' },
  { key: 'graph', label: 'Project Graph' },
]

function LinkList({ links, empty }: { links: RelationLink[]; empty: string }) {
  if (links.length === 0) return <p className="muted">{empty}</p>
  return (
    <ul className="relation-list">
      {links.map((l) => (
        <li key={l.id}>
          <span className="badge">{l.label}</span> <Link to={nodePath(l.nodeType, l.nodeId)}>{l.nodeTitle}</Link>{' '}
          <span className="muted small">{l.nodeType}</span>
          {l.nodeStatus === 'ARCHIVED' && <span className="muted small"> (보관됨)</span>}
        </li>
      ))}
    </ul>
  )
}

export function ProjectDetailPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = (TABS.find((t) => t.key === params.get('tab'))?.key ?? 'overview') as Tab
  const { data: project, isLoading, error, refetch } = useQuery({
    queryKey: projectKeys.detail(id),
    queryFn: () => getProject(id),
  })
  if (isLoading) return <LoadingState />
  if (error || !project) return <ErrorState error={error} onRetry={() => refetch()} />

  const editable = project.status !== 'TRASHED'
  // 프로젝트에서 본 incoming 관계가 "이 프로젝트에 연결된 것들"이다(별도 junction 없음, PROJ-03).
  const incoming = project.relations.incoming
  const knowledge = incoming.filter((l) => l.type === 'USED_IN' && l.nodeType !== 'SNIPPET')
  const snippets = incoming.filter((l) => l.type === 'USED_IN' && l.nodeType === 'SNIPPET')
  const problems = incoming.filter((l) => l.type === 'OCCURRED_IN' || l.type === 'APPLIED_IN')

  return (
    <article>
      <div className="row">
        <span className="badge">{PROJECT_STATUS_LABEL[project.project.projectStatus]}</span>
        <h1>{project.title}</h1>
        <FavoriteButton nodeId={project.id} favorite={project.favorite} />
      </div>
      {project.status !== 'ACTIVE' && <p className="muted">상태: {project.status === 'ARCHIVED' ? '보관됨' : '휴지통'}</p>}
      <div className="tabs" role="tablist" aria-label="Project 탭">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => {
              const next = new URLSearchParams(params)
              if (t.key === 'overview') next.delete('tab')
              else next.set('tab', t.key)
              setParams(next, { replace: true })
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && <Overview project={project} />}
      {tab === 'knowledge' && <LinkList links={knowledge} empty="이 프로젝트에서 쓴 지식을 연결해 보세요." />}
      {tab === 'problems' && <LinkList links={problems} empty="이 프로젝트에서 겪은 오류와 적용한 해결을 연결해 보세요." />}
      {tab === 'snippets' && <LinkList links={snippets} empty="이 프로젝트에서 쓴 Snippet을 연결해 보세요." />}
      {tab === 'graph' && (
        <Suspense fallback={<LoadingState label="그래프를 불러오는 중..." />}>
          <ProjectGraphTab projectId={project.id} />
        </Suspense>
      )}

      <div className="row">
        {editable && <Link to={`/projects/${project.id}/edit`}>편집</Link>}
        <StatusActions node={project} />
      </div>
      <RelatedNodes nodeId={project.id} nodeType="PROJECT" relations={project.relations} editable={editable} />
    </article>
  )
}

function Overview({ project }: { project: ProjectDetail }) {
  const p = project.project
  return (
    <div>
      {project.summary && <p className="muted">{project.summary}</p>}
      {project.description && <MarkdownView source={project.description} />}
      <dl>
        {p.repositoryUrl && (
          <>
            <dt>저장소</dt>
            <dd>
              <a href={p.repositoryUrl} target="_blank" rel="noopener noreferrer">
                {p.repositoryUrl} (새 창)
              </a>
            </dd>
          </>
        )}
        {(p.startedOn || p.endedOn) && (
          <>
            <dt>기간</dt>
            <dd>
              {p.startedOn ?? '?'} ~ {p.endedOn ?? '진행 중'}
            </dd>
          </>
        )}
      </dl>
      {project.tags.length > 0 && (
        <ul className="chips" aria-label="태그">
          {project.tags.map((tag) => (
            <li key={tag.id} className="chip">
              {tag.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function ProjectFormPage() {
  const { id } = useParams()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: projectKeys.detail(id ?? ''),
    queryFn: () => getProject(id!),
    enabled: !!id,
  })
  if (id && isLoading) return <LoadingState />
  if (id && (error || !data)) return <ErrorState error={error} onRetry={() => refetch()} />
  return <ProjectForm initial={data} />
}

function ProjectForm({ initial }: { initial?: ProjectDetail }) {
  const navigate = useNavigate()
  const invalidate = useInvalidateRelationViews()
  const [title, setTitle] = useState(initial?.title ?? '')
  const [summary, setSummary] = useState(initial?.summary ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [status, setStatus] = useState<ProjectStatus>(initial?.project.projectStatus ?? 'ACTIVE')
  const [repositoryUrl, setRepositoryUrl] = useState(initial?.project.repositoryUrl ?? '')
  const [startedOn, setStartedOn] = useState(initial?.project.startedOn ?? '')
  const [endedOn, setEndedOn] = useState(initial?.project.endedOn ?? '')
  const [tags, setTags] = useState<Tag[]>(initial?.tags ?? [])

  const dirty = initial
    ? title !== initial.title || summary !== (initial.summary ?? '') || description !== (initial.description ?? '') ||
      status !== initial.project.projectStatus || repositoryUrl !== (initial.project.repositoryUrl ?? '') ||
      startedOn !== (initial.project.startedOn ?? '') || endedOn !== (initial.project.endedOn ?? '') ||
      tags.map((t) => t.id).join() !== initial.tags.map((t) => t.id).join()
    : !!(title || summary || description || repositoryUrl || startedOn || endedOn)

  return (
    <div>
      <h1>{initial ? 'Project 편집' : '새 Project'}</h1>
      <SubtypeFormFrame
        title={title}
        onTitle={setTitle}
        summary={summary}
        onSummary={setSummary}
        tags={tags}
        onTags={setTags}
        dirty={dirty}
        save={async () => {
          const input = {
            title, summary, description, projectStatus: status, repositoryUrl,
            startedOn: startedOn || undefined, endedOn: endedOn || undefined,
            // 날짜를 비우면 지움(빈 값은 "변경 없음"과 구분해야 한다).
            clearStartedOn: !!initial?.project.startedOn && !startedOn,
            clearEndedOn: !!initial?.project.endedOn && !endedOn,
            tagIds: tags.map((t) => t.id),
          }
          const saved = initial ? await updateProject(initial.id, initial.version, input) : await createProject(input)
          await invalidate()
          return saved
        }}
        onSaved={(saved) => navigate(`/projects/${saved.id}`)}
        onCancel={() => navigate(-1)}
      >
        <label>
          상태
          <select value={status} onChange={(e) => setStatus(e.target.value as ProjectStatus)}>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {PROJECT_STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </label>
        <label>
          설명 (Markdown)
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={5} />
        </label>
        <label>
          저장소 URL (선택)
          <input value={repositoryUrl} onChange={(e) => setRepositoryUrl(e.target.value)} placeholder="https://github.com/…" maxLength={500} />
        </label>
        <div className="row">
          <label>
            시작일
            <input type="date" value={startedOn} onChange={(e) => setStartedOn(e.target.value)} />
          </label>
          <label>
            종료일
            <input type="date" value={endedOn} onChange={(e) => setEndedOn(e.target.value)} />
          </label>
        </div>
      </SubtypeFormFrame>
    </div>
  )
}
