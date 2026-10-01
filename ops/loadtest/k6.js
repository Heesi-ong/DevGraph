// NFR-01 측정(설계서 §22, §22.1): 20 concurrent user, 목록/상세 p95 ≤ 500ms, 검색 p95 ≤ 800ms, focus graph(200 node) p95 ≤ 1s.
// 사용: k6 run -e BASE=http://localhost:8088 -e EMAIL=... -e PASSWORD=... -e HUB_ID=<고차수 node id> [-e DURATION=60s] ops/loadtest/k6.js
import http from 'k6/http'
import { check, fail } from 'k6'

const BASE = __ENV.BASE || 'http://localhost:8088'
const API = `${BASE}/api/v1`

export const options = {
  scenarios: {
    users: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '60s' },
  },
  thresholds: {
    'http_req_duration{kind:list}': ['p(95)<500'],
    'http_req_duration{kind:detail}': ['p(95)<500'],
    'http_req_duration{kind:search}': ['p(95)<800'],
    'http_req_duration{kind:graph}': ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

const WORDS = ['jpa', 'transaction', 'hibernate', 'spring', 'index', 'cache', 'docker', 'react', '트랜잭션', '인덱스', '쿼리', '동시성', 'lazy fetch', 'deadlock lock']
const PARTIAL = ['Demo1', 'public class', 'run()', 'jpa tran']

export function setup() {
  const login = http.post(`${API}/auth/login`, JSON.stringify({ email: __ENV.EMAIL, password: __ENV.PASSWORD }), { headers: { 'Content-Type': 'application/json' } })
  if (login.status !== 200) fail(`login failed: ${login.status}`)
  const token = login.json('accessToken')
  const headers = { Authorization: `Bearer ${token}` }
  // 상세 조회용 id: 목록 몇 쪽에서 모은다.
  const ids = []
  let cursor = ''
  for (let i = 0; i < 6; i++) {
    const page = http.get(`${API}/nodes?size=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { headers }).json()
    page.items.forEach((n) => ids.push(n.id))
    if (!page.hasMore) break
    cursor = page.cursor
  }
  return { token, ids }
}

const pick = (a) => a[Math.floor(Math.random() * a.length)]

export default function (data) {
  const params = (kind) => ({ headers: { Authorization: `Bearer ${data.token}` }, tags: { kind } })
  const r = Math.random()
  let res
  if (r < 0.3) {
    const type = pick(['', '&type=CONCEPT', '&type=NOTE'])
    res = http.get(`${API}/nodes?size=20${type}`, params('list'))
  } else if (r < 0.6) {
    res = http.get(`${API}/nodes/${pick(data.ids)}`, params('detail'))
  } else if (r < 0.85) {
    const q = Math.random() < 0.7 ? pick(WORDS) : pick(PARTIAL)
    res = http.get(`${API}/search?q=${encodeURIComponent(q)}&size=20`, params('search'))
  } else {
    // 절반은 허브(차수 ≥ 200)를 중심으로, 절반은 임의 Node로 depth 2, 최대 200 node
    const id = Math.random() < 0.5 ? __ENV.HUB_ID : pick(data.ids)
    res = http.get(`${API}/graph/focus/${id}?depth=2&maxNodes=200`, params('graph'))
  }
  check(res, { 'status 200': (x) => x.status === 200 })
}
