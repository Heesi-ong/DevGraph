-- 설계서 §12.2 공통 규칙의 baseline.
-- PK는 gen_random_uuid()(PostgreSQL 13+ 내장, pgcrypto 불필요)로 생성한다.
-- pg_trgm은 §12.3 title/code trigram index와 §9.5 code 유사도 검색에 필요하다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
