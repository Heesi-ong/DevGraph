-- NFR 측정용 대표 dataset(설계서 §22, §22.1). 대상 workspace는 psql 변수 :ws, 작성자는 :uid.
-- 구성: CONCEPT 8k + NOTE 12k + SNIPPET 8k(현재 버전 1개씩) = 28k Node,
--       RELATED_TO edge: 무작위 2×N 개(평균 degree ≈ 4) + 상위 1% hub Node가 각 200개 이웃을 갖는다.
-- 이 스크립트는 측정용 계정의 workspace에만 넣는다. 사용: psql -v ws=... -v uid=... -f seed.sql
\set ON_ERROR_STOP on
\timing on
SELECT setseed(0.42);

CREATE TABLE IF NOT EXISTS pg_temp.words AS SELECT ARRAY[
 'jpa','transaction','hibernate','spring','boot','lazy','fetch','join','index','query','cache','redis','kafka','docker','nginx',
 'react','hook','state','effect','router','typescript','generic','promise','async','thread','lock','deadlock','isolation','mvcc','vacuum',
 '트랜잭션','영속성','컨텍스트','지연','로딩','인덱스','쿼리','캐시','동시성','락','배포','컨테이너','보안','인증','토큰','세션','테스트','리팩터링','설계','아키텍처'] AS w;

-- random()은 행마다 평가되므로 단어 배열에서 직접 고른다(서브쿼리로 고르면 여러 행이 매칭된다).
CREATE OR REPLACE FUNCTION pg_temp.sentence(k int) RETURNS text LANGUAGE sql AS $$
  SELECT string_agg((SELECT w[1 + floor(random()*50)::int] FROM pg_temp.words), ' ') FROM generate_series(1, k)
$$;

-- Node
INSERT INTO knowledge_nodes (id, workspace_id, created_by, node_type, title, summary, body_md, status, created_at, updated_at)
SELECT gen_random_uuid(), :'ws', :'uid',
       CASE WHEN g <= 8000 THEN 'CONCEPT' WHEN g <= 20000 THEN 'NOTE' ELSE 'SNIPPET' END,
       left(pg_temp.sentence(4) || ' #' || g, 200),
       pg_temp.sentence(10),
       CASE WHEN g > 20000 THEN NULL ELSE pg_temp.sentence(120) END,
       CASE WHEN g % 50 = 0 THEN 'ARCHIVED' ELSE 'ACTIVE' END,
       now() - (random() * interval '365 days'), now() - (random() * interval '30 days')
FROM generate_series(1, 28000) g;

INSERT INTO snippets (node_id, workspace_id, language, current_version_no)
SELECT id, workspace_id, (ARRAY['java','typescript','sql','python','yaml'])[1 + floor(random()*5)::int], 1
FROM knowledge_nodes WHERE workspace_id = :'ws' AND node_type = 'SNIPPET';

INSERT INTO snippet_versions (workspace_id, snippet_node_id, version_no, code, content_hash, created_by)
SELECT workspace_id, id, 1,
       'public class Demo' || row_number() OVER () || E' {\n  // ' || pg_temp.sentence(8) || E'\n  void run() { ' || pg_temp.sentence(6) || E' }\n}\n',
       md5(id::text) || md5(id::text || 'x'), :'uid'
FROM knowledge_nodes WHERE workspace_id = :'ws' AND node_type = 'SNIPPET';

-- 순번 → Node id (edge 생성용)
CREATE TEMP TABLE nodes_n AS
SELECT id, row_number() OVER (ORDER BY id) AS n FROM knowledge_nodes WHERE workspace_id = :'ws' AND status = 'ACTIVE';
CREATE UNIQUE INDEX ON nodes_n (n);
SELECT count(*) AS active_nodes FROM nodes_n \gset
SELECT id AS rel_type FROM relation_types WHERE key = 'RELATED_TO' AND workspace_id IS NULL \gset

-- 무작위 edge: symmetric 타입은 (작은 id, 큰 id) 정규형으로 넣는다.
WITH pairs AS (
  SELECT 1 + floor(random() * :active_nodes)::int AS a, 1 + floor(random() * :active_nodes)::int AS b
  FROM generate_series(1, :active_nodes * 2)
), resolved AS (
  SELECT LEAST(x.id, y.id) AS s, GREATEST(x.id, y.id) AS t
  FROM pairs p JOIN nodes_n x ON x.n = p.a JOIN nodes_n y ON y.n = p.b WHERE p.a <> p.b
)
INSERT INTO knowledge_relations (workspace_id, source_node_id, target_node_id, relation_type_id, created_by)
SELECT DISTINCT :'ws'::uuid, s, t, :'rel_type'::uuid, :'uid'::uuid FROM resolved
ON CONFLICT DO NOTHING;

-- hub: 상위 1%(280개)가 각 200개 이웃
WITH hubs AS (SELECT n AS hn, id AS hid FROM nodes_n WHERE n <= :active_nodes / 100),
     spokes AS (SELECT h.hid, 1 + floor(random() * :active_nodes)::int AS sn FROM hubs h, generate_series(1, 200)),
     resolved AS (SELECT LEAST(s.hid, x.id) AS s, GREATEST(s.hid, x.id) AS t FROM spokes s JOIN nodes_n x ON x.n = s.sn WHERE x.id <> s.hid)
INSERT INTO knowledge_relations (workspace_id, source_node_id, target_node_id, relation_type_id, created_by)
SELECT DISTINCT :'ws'::uuid, s, t, :'rel_type'::uuid, :'uid'::uuid FROM resolved
ON CONFLICT DO NOTHING;

ANALYZE;
SELECT node_type, status, count(*) FROM knowledge_nodes WHERE workspace_id = :'ws' GROUP BY 1, 2 ORDER BY 1, 2;
SELECT count(*) AS edges, round(2.0 * count(*) / :active_nodes, 2) AS avg_degree FROM knowledge_relations WHERE workspace_id = :'ws';
