package com.devgraph.snippet.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설계서 §17.5 Secret 감지(서버 측). 휴리스틱이라 오탐이 있으므로 저장을 막지 않고 확인 절차로 이어진다.
 * 결과에는 **종류와 줄 번호만** 담는다 — 의심 값 자체는 로그·응답 어디에도 내보내지 않는다.
 * ponytail: 고엔트로피 문자열 감지는 해시·UUID 오탐이 많아 제외했다. 실사용 오탐률을 보고 추가한다.
 */
public final class SecretScanner {

	public enum Kind {
		PRIVATE_KEY(true), API_KEY(false), JWT(false), CREDENTIAL_ASSIGNMENT(false), CONNECTION_STRING(false);

		private final boolean highRisk;

		Kind(boolean highRisk) {
			this.highRisk = highRisk;
		}

		public boolean isHighRisk() {
			return highRisk;
		}
	}

	public record Finding(Kind kind, int line) {
	}

	private record Rule(Kind kind, Pattern pattern) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(Kind.PRIVATE_KEY, Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY")),
			new Rule(Kind.API_KEY, Pattern.compile(
					"\\b(?:AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}"
							+ "|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35})")),
			new Rule(Kind.JWT, Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
			// scheme과 자격 증명 길이에 상한을 둬 "a://a://..." 같은 병적 입력에서도 시작 위치마다의 비용이 상수다.
			new Rule(Kind.CONNECTION_STRING,
					Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]{0,30}://[^\\s:/@$<{]{1,128}:[^\\s@/$<{]{1,128}@")));

	// password=..., "token": "...", DB_PASSWORD=..., AWS_SECRET_ACCESS_KEY=... 같은 대입. 앞에는 접두어가 올 수 있고
	// (\b은 '_' 뒤를 경계로 보지 않아 DB_PASSWORD를 놓친다), 뒤에는 key/token/value 계열 접미어만 허용한다
	// (tokenizer, token_expiry 같은 이름을 잡지 않으려는 것). 값이 자리표시자·환경변수·함수 호출이면 제외한다.
	private static final Pattern ASSIGNMENT = Pattern.compile(
			"(?i)(?:password|passwd|secret|token|api[_-]?key)(?:[_-]?(?:access[_-]?)?(?:key|token|value))?"
					+ "[\"']?\\s*[:=]\\s*[\"']?([^\\s\"';,]{8,})");
	private static final Pattern PLACEHOLDER = Pattern.compile(
			"(?i)^[$<{%*]|[(){}]|process\\.env|getenv|example|placeholder|your[_-]|changeme|xxxx");

	private SecretScanner() {
	}

	// 한 줄이 512KB인 minified 코드도 들어올 수 있다. 정규식을 줄 전체에 돌리면 백트래킹이 O(n²)로 커져
	// 요청 하나가 스레드를 오래 붙잡는다. 줄을 고정 크기 창(겹침 포함)으로 나눠 최악 비용을 창 크기로 묶는다.
	// 겹침(OVERLAP)은 창 경계에 걸친 패턴(최대 수백 자)을 놓치지 않기 위한 값이다.
	private static final int WINDOW = 1024;
	private static final int OVERLAP = 256;

	public static List<Finding> scan(String code) {
		List<Finding> findings = new ArrayList<>();
		String[] lines = code.split("\\r\\n|\\n|\\r", -1);
		for (int i = 0; i < lines.length; i++) {
			Set<Kind> seen = EnumSet.noneOf(Kind.class);
			String line = lines[i];
			for (int start = 0; ; start += WINDOW - OVERLAP) {
				int end = Math.min(line.length(), start + WINDOW);
				scanWindow(line.substring(start, end), seen);
				if (end == line.length()) {
					break;
				}
			}
			for (Kind kind : seen) {
				findings.add(new Finding(kind, i + 1));
			}
		}
		return findings;
	}

	private static void scanWindow(String text, Set<Kind> seen) {
		for (Rule rule : RULES) {
			if (!seen.contains(rule.kind()) && rule.pattern().matcher(text).find()) {
				seen.add(rule.kind());
			}
		}
		if (seen.contains(Kind.CREDENTIAL_ASSIGNMENT)) {
			return;
		}
		Matcher assignment = ASSIGNMENT.matcher(text);
		while (assignment.find()) {
			if (!PLACEHOLDER.matcher(assignment.group(1)).find()) {
				seen.add(Kind.CREDENTIAL_ASSIGNMENT);
				return;
			}
		}
	}

	public static boolean hasHighRisk(List<Finding> findings) {
		return findings.stream().anyMatch(f -> f.kind().isHighRisk());
	}
}
