# DevGraph 보안 점검표 (Phase 7)

점검일: 2026-10-01. 환경: docker-compose.prod.yml 스택(nginx → Spring Boot prod 프로파일 → PostgreSQL 16). ✅ 자동/실측으로 확인, ⚠️ 부분/제한, ⬜ 하지 않음.

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | 인증 없이 `/api/v1` 접근 불가 | ✅ | `ApiAuthSweepIntegrationTest`: 모든 매핑을 열거해 공개 목록 외 전부 401임을 검사. 새 endpoint가 보호 없이 추가되면 테스트가 깨진다 |
| 2 | Tenant 격리(다른 Workspace의 Node/Tag/Snippet/Relation/Project/Resource/Export/검색) | ✅ | 도메인별 통합 테스트(`otherWorkspace…`, `…NeverLeak`, `jobsAreVisibleOnlyToTheirWorkspace…`). 외부 자원 존재 여부를 404로 통일 |
| 3 | 비밀번호 저장 | ✅ | Argon2(Spring Security), 평문·해시는 로그/응답에 없음. 요청 로그 테스트가 비밀번호·토큰·검색어·이메일의 로그 유출을 검사 |
| 4 | 세션 | ✅ | Refresh 회전 + 재사용 감지(family 폐기), HttpOnly+Secure+SameSite 쿠키, CSRF double-submit(refresh/logout), 기기별 세션 폐기, 비밀번호 변경 시 선택적 전체 폐기 |
| 5 | 위험 작업 재인증(영구 삭제·Export·계정 삭제) | ✅ | 세션 family 귀속 5분 일회성 토큰, 목적·대상 불일치 거부, 동시 소비 시 정확히 한 번만 성공(통합 테스트) |
| 6 | Rate limit | ✅ | 로그인 실패 5/분(이메일+IP 대역), 재인증 실패 5/분, 검색 60/분, 변경 120/분, Export 10분 간격. prod 스택에서 6번째 실패가 `429 + Retry-After` 확인, 다른 이메일은 영향 없음 |
| 7 | 보안 응답 헤더 | ✅ | API(Spring Security): CSP, nosniff, X-Frame-Options DENY, Referrer-Policy, Permissions-Policy, no-store, HSTS(HTTPS일 때만). SPA 정적 응답(nginx): 같은 계열 + 자산 장기 캐시. 각 응답에 헤더가 한 번만 붙는지 prod 스택에서 확인 |
| 8 | 클라이언트 IP 위조 | ✅ | nginx가 `X-Forwarded-For`를 자기가 본 주소로 덮어쓴다. 위조 헤더를 보내도 감사 로그 IP 대역이 바뀌지 않음을 prod 스택에서 확인 |
| 9 | 입력 처리 | ✅ | SQL은 파라미터 바인딩, LIKE 이스케이프, 위험 URL 스킴 거부(Resource), 위험 코드/본문은 텍스트로만 렌더(Playwright가 prod 빌드에서 실행되지 않음을 확인) |
| 10 | Export 안전성 | ✅ | 일회성 다운로드 토큰(해시 저장, 조회 시 재발급, 만료·사용 후 무효), ZIP 경로 검사, 크기 상한, 실패 사유 일반화(내부 경로 미노출), 감사 로그에 토큰 없음 |
| 11 | 로그 | ✅ | 요청당 1줄 JSON: 본문·헤더·쿠키·query 미기록, `userIdHash`만 기록, 클라이언트가 보낸 request id를 신뢰하지 않음 |
| 12 | 의존성 취약점 | ⚠️ | Trivy(이미지) 기준 backend Java 의존성 CRITICAL/HIGH **0**(Tomcat 11.0.26, BouncyCastle 1.86, Jackson 3.1.7/2.21.7로 상향해 해소), OS 패키지 0. 남은 것: 기반 이미지에 포함된 `usr/bin/pebble`(Go 바이너리) HIGH 14건 — 우리 코드가 실행/노출하지 않지만 기반 이미지 갱신 때 사라지는지 재검사 필요. 프론트 `npm audit --omit=dev`: CRITICAL/HIGH 0, low 2(monaco-editor가 포함한 dompurify; 우리가 DOMPurify를 직접 쓰지 않으며 `npm audit fix --force`는 monaco 다운그레이드라 보류). dev 전용 moderate 2(vitest 계열) |
| 13 | 컨테이너 | ✅ | backend 비root 사용자, backend/postgres 호스트 포트 미공개, 시크릿은 환경변수(`.env`는 gitignore) |
| 14 | 시크릿 스캔(저장소) | ✅ | gitleaks로 전체 이력(14커밋) 스캔: 발견 7건은 모두 시크릿 감지기 테스트의 의도적 가짜 fixture(`SecretScannerTest`, `snippet.spec.ts`)여서 해당 경로만 `.gitleaks.toml`로 제외했고, 그 밖은 0건. CI `secrets` 잡이 매 푸시마다 전체 이력을 다시 스캔한다 |
| 15 | 동적 보안 스캔(ZAP 등), 침투 테스트 | ⬜ | 하지 않았다. 위 점검은 "OWASP Top 10 기본 점검"의 자동화 가능한 부분이며 전문 점검을 대체하지 않는다 |
| 16 | 백업 암호화·오프사이트 | ✅ | `BACKUP_PASSPHRASE_FILE`로 gpg AES256 암호화(평문 덤프가 디스크에 남지 않음), 암호화된 덤프로 복원 리허설 통과, 암호화 없는 업로드는 거부. 전송 명령·암호 보관은 운영자 책임(Runbook §2) |
| 17 | 지표 노출 | ✅ | 지표는 분리된 management 포트에서만 응답. 외부(nginx·호스트)에서 접근 불가, main 포트 설정 실수에도 보안 규칙이 막음, 지표에 개인 내용 없음(`MetricsExposureIntegrationTest`) |

## OWASP Top 10 대응 요약

A01 접근 제어: 1, 2, 5 · A02 암호화 실패: 3, 4, 7(HSTS) · A03 인젝션: 9 · A04 불안전 설계: 5, 6 · A05 보안 설정 오류: 7, 13 · A06 취약 컴포넌트: 12 · A07 인증 실패: 3, 4, 6 · A08 무결성 실패: 10(manifest checksum), 12 · A09 로깅·모니터링: 11 · A10 SSRF: 서버가 사용자 URL을 가져오는 기능이 없음(Resource URL은 저장만).
