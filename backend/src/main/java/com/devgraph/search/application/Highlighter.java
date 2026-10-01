package com.devgraph.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.devgraph.search.application.SearchViews.Segment;

/**
 * 원문을 `{text, matched}` 구간으로 나눈다. 서버는 HTML을 만들지 않고 text는 원문 그대로다(§14.7) —
 * 프론트가 React 요소로 그리므로 `dangerouslySetInnerHTML`이 필요 없다.
 * 대소문자를 무시하고 모든 검색어의 모든 등장 위치를 표시한다(겹치면 합친다).
 */
final class Highlighter {

	private Highlighter() {
	}

	static List<String> terms(String query) {
		List<String> terms = new ArrayList<>();
		for (String part : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
			if (!part.isEmpty() && !terms.contains(part)) {
				terms.add(part);
			}
		}
		return terms;
	}

	/**
	 * @param leadingCut/trailingCut 발췌가 원문의 앞/뒤를 잘라 낸 경우 줄임표 구간을 붙인다.
	 */
	static List<Segment> segments(String text, List<String> terms, boolean leadingCut, boolean trailingCut) {
		List<Segment> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		if (leadingCut) {
			out.add(new Segment("…", false));
		}
		String lower = text.toLowerCase(Locale.ROOT);
		// 소문자 변환으로 길이가 달라지는 드문 문자가 있으면 위치가 어긋나므로 표시 없이 원문을 그대로 준다.
		boolean aligned = lower.length() == text.length();
		List<int[]> ranges = aligned ? matchRanges(lower, terms) : List.of();
		int cursor = 0;
		for (int[] range : ranges) {
			if (range[0] > cursor) {
				out.add(new Segment(text.substring(cursor, range[0]), false));
			}
			out.add(new Segment(text.substring(range[0], range[1]), true));
			cursor = range[1];
		}
		if (cursor < text.length()) {
			out.add(new Segment(text.substring(cursor), false));
		}
		if (trailingCut) {
			out.add(new Segment("…", false));
		}
		return out;
	}

	static boolean hasMatch(List<Segment> segments) {
		return segments.stream().anyMatch(Segment::matched);
	}

	private static List<int[]> matchRanges(String lower, List<String> terms) {
		List<int[]> ranges = new ArrayList<>();
		for (String term : terms) {
			int from = 0;
			while (from <= lower.length() - term.length()) {
				int at = lower.indexOf(term, from);
				if (at < 0) {
					break;
				}
				ranges.add(new int[] { at, at + term.length() });
				from = at + term.length();
			}
		}
		ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
		List<int[]> merged = new ArrayList<>();
		for (int[] range : ranges) {
			if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
				merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
			} else {
				merged.add(range);
			}
		}
		return merged;
	}
}
