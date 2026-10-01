package com.devgraph.snippet.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 두 코드의 줄 단위 diff(SNP-07). Myers O(ND) 알고리즘이고 편집 거리 상한({@link #MAX_EDITS})을 넘으면 계산하지 않는다 —
 * 큰 입력 두 개가 서버 CPU/메모리를 오래 잡지 못하게 하는 장치다.
 *
 * 줄은 '\n' 기준으로 나누며 '\r'는 줄 내용에 남긴다(CRLF만 다른 두 버전도 차이로 보인다). 마지막 줄이 줄바꿈으로 끝나지
 * 않으면 그 사실도 줄 정체성에 포함해, "b"와 "b\n"이 같은 코드로 보이지 않는다.
 */
public final class LineDiff {

	public static final int MAX_EDITS = 2_000;
	public static final int MAX_LINES = 40_000;
	private static final int CONTEXT = 3;

	public enum Type { CONTEXT, ADD, DELETE }

	/** oldNo/newNo는 1부터, 해당 쪽에 없는 줄은 0. {@code noEol}은 이 줄이 파일 끝에서 줄바꿈 없이 끝났다는 뜻이다. */
	public record Line(Type type, int oldNo, int newNo, String text, boolean noEol) {
	}

	public record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<Line> lines) {
	}

	public record Result(List<Hunk> hunks, int added, int deleted) {
	}

	public static class TooLargeException extends RuntimeException {
		public TooLargeException() {
			super("diff too large");
		}
	}

	private record Split(List<String> texts, List<String> keys, boolean lastNoEol) {
	}

	private LineDiff() {
	}

	public static Result diff(String oldCode, String newCode) {
		Split a = split(oldCode);
		Split b = split(newCode);
		if (a.keys.size() > MAX_LINES || b.keys.size() > MAX_LINES) {
			throw new TooLargeException();
		}
		List<Line> script = script(a, b);
		int added = 0;
		int deleted = 0;
		for (Line l : script) {
			if (l.type == Type.ADD) added++;
			else if (l.type == Type.DELETE) deleted++;
		}
		return new Result(hunks(script), added, deleted);
	}

	private static Split split(String code) {
		List<String> texts = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		if (code.isEmpty()) {
			return new Split(texts, keys, false);
		}
		boolean endsWithNewline = code.endsWith("\n");
		String body = endsWithNewline ? code.substring(0, code.length() - 1) : code;
		String[] parts = body.split("\n", -1);
		for (int i = 0; i < parts.length; i++) {
			boolean last = i == parts.length - 1;
			texts.add(parts[i]);
			keys.add(last && !endsWithNewline ? parts[i] + "\u0000noeol" : parts[i]);
		}
		return new Split(texts, keys, !endsWithNewline);
	}

	/** 공통 접두/접미를 걷어낸 뒤 가운데만 Myers로 계산한다. */
	private static List<Line> script(Split a, Split b) {
		List<String> x = a.keys;
		List<String> y = b.keys;
		int prefix = 0;
		while (prefix < x.size() && prefix < y.size() && x.get(prefix).equals(y.get(prefix))) {
			prefix++;
		}
		int suffix = 0;
		while (suffix < x.size() - prefix && suffix < y.size() - prefix
				&& x.get(x.size() - 1 - suffix).equals(y.get(y.size() - 1 - suffix))) {
			suffix++;
		}
		List<Line> out = new ArrayList<>();
		for (int i = 0; i < prefix; i++) {
			out.add(line(Type.CONTEXT, i + 1, i + 1, a, i));
		}
		List<String> mx = x.subList(prefix, x.size() - suffix);
		List<String> my = y.subList(prefix, y.size() - suffix);
		for (int[] op : myers(mx, my)) { // {kind, indexInMx or -1, indexInMy or -1}
			if (op[0] == 0) {
				out.add(line(Type.DELETE, prefix + op[1] + 1, 0, a, prefix + op[1]));
			} else if (op[0] == 1) {
				out.add(line(Type.ADD, 0, prefix + op[2] + 1, b, prefix + op[2]));
			} else {
				out.add(line(Type.CONTEXT, prefix + op[1] + 1, prefix + op[2] + 1, a, prefix + op[1]));
			}
		}
		for (int i = 0; i < suffix; i++) {
			int oi = x.size() - suffix + i;
			int ni = y.size() - suffix + i;
			out.add(line(Type.CONTEXT, oi + 1, ni + 1, a, oi));
		}
		return out;
	}

	private static Line line(Type type, int oldNo, int newNo, Split side, int index) {
		boolean noEol = side.lastNoEol && index == side.texts.size() - 1;
		return new Line(type, oldNo, newNo, side.texts.get(index), noEol);
	}

	/** Myers 최단 편집 스크립트. 반환 원소는 {0=삭제|1=추가|2=공통, x 인덱스, y 인덱스}. */
	private static List<int[]> myers(List<String> x, List<String> y) {
		int n = x.size();
		int m = y.size();
		if (n == 0 && m == 0) {
			return List.of();
		}
		int max = Math.min(n + m, MAX_EDITS);
		int offset = max + 1;
		List<int[]> trace = new ArrayList<>();
		int[] v = new int[2 * max + 3];
		for (int d = 0; d <= max; d++) {
			trace.add(v.clone());
			for (int k = -d; k <= d; k += 2) {
				int px;
				if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
					px = v[offset + k + 1];
				} else {
					px = v[offset + k - 1] + 1;
				}
				int py = px - k;
				while (px < n && py < m && x.get(px).equals(y.get(py))) {
					px++;
					py++;
				}
				v[offset + k] = px;
				if (px >= n && py >= m) {
					return backtrack(trace, x, y, d, offset);
				}
			}
		}
		throw new TooLargeException();
	}

	private static List<int[]> backtrack(List<int[]> trace, List<String> x, List<String> y, int dEnd, int offset) {
		List<int[]> ops = new ArrayList<>();
		int px = x.size();
		int py = y.size();
		for (int d = dEnd; d > 0; d--) {
			int[] v = trace.get(d); // 이 d를 시작하기 직전의 v(= d-1 단계 결과)
			int k = px - py;
			int prevK;
			if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
				prevK = k + 1;
			} else {
				prevK = k - 1;
			}
			int prevX = v[offset + prevK];
			int prevY = prevX - prevK;
			while (px > prevX && py > prevY) {
				px--;
				py--; // 대각선(공통 줄)
				ops.add(new int[] { 2, px, py });
			}
			if (px == prevX) {
				ops.add(new int[] { 1, -1, prevY }); // 위로: y의 prevY번째 줄 추가
			} else {
				ops.add(new int[] { 0, prevX, -1 }); // 오른쪽: x의 prevX번째 줄 삭제
			}
			px = prevX;
			py = prevY;
		}
		while (px > 0 && py > 0) { // d=0 단계의 시작 대각선
			px--;
			py--;
			ops.add(new int[] { 2, px, py });
		}
		java.util.Collections.reverse(ops);
		return ops;
	}

	private static List<Hunk> hunks(List<Line> script) {
		List<Hunk> hunks = new ArrayList<>();
		int i = 0;
		int size = script.size();
		while (i < size) {
			while (i < size && script.get(i).type == Type.CONTEXT) {
				i++;
			}
			if (i >= size) {
				break;
			}
			int start = Math.max(0, i - CONTEXT);
			int end = i;
			int lastChange = i;
			// 변경 사이의 공통 줄이 2*CONTEXT 이하이면 같은 hunk로 묶는다.
			while (end < size) {
				if (script.get(end).type != Type.CONTEXT) {
					lastChange = end;
				} else if (end - lastChange > 2 * CONTEXT) {
					break;
				}
				end++;
			}
			int stop = Math.min(size, lastChange + 1 + CONTEXT);
			List<Line> lines = new ArrayList<>(script.subList(start, stop));
			hunks.add(header(lines));
			i = stop;
		}
		return hunks;
	}

	private static Hunk header(List<Line> lines) {
		int oldStart = 0;
		int newStart = 0;
		int oldCount = 0;
		int newCount = 0;
		for (Line l : lines) {
			if (l.type != Type.ADD) {
				if (oldStart == 0) oldStart = l.oldNo;
				oldCount++;
			}
			if (l.type != Type.DELETE) {
				if (newStart == 0) newStart = l.newNo;
				newCount++;
			}
		}
		return new Hunk(oldStart, oldCount, newStart, newCount, lines);
	}
}
