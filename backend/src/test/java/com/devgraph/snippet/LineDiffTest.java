package com.devgraph.snippet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.devgraph.snippet.domain.LineDiff;
import com.devgraph.snippet.domain.LineDiff.Line;
import com.devgraph.snippet.domain.LineDiff.Type;

class LineDiffTest {

	/** hunks의 줄들을 이어 붙여 old/new를 복원할 수는 없으므로, 전체 스크립트를 hunk 밖 줄까지 포함해 검증한다. */
	private static int editDistance(List<String> a, List<String> b) {
		int[][] lcs = new int[a.size() + 1][b.size() + 1];
		for (int i = a.size() - 1; i >= 0; i--) {
			for (int j = b.size() - 1; j >= 0; j--) {
				lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}
		return a.size() + b.size() - 2 * lcs[0][0];
	}

	@Test
	void reportsExactLineChangesWithNumbersAndContext() {
		var result = LineDiff.diff("a\nb\nc\nd\ne\nf\ng\nh\n", "a\nb\nC\nd\ne\nf\ng\nh\ni\n");
		assertThat(result.added()).isEqualTo(2);
		assertThat(result.deleted()).isEqualTo(1);
		assertThat(result.hunks()).hasSize(1);
		var lines = result.hunks().get(0).lines();
		assertThat(lines.stream().filter(l -> l.type() == Type.DELETE).map(Line::text)).containsExactly("c");
		assertThat(lines.stream().filter(l -> l.type() == Type.ADD).map(Line::text)).containsExactly("C", "i");
		var del = lines.stream().filter(l -> l.type() == Type.DELETE).findFirst().orElseThrow();
		assertThat(del.oldNo()).isEqualTo(3);
		assertThat(del.newNo()).isZero();
		assertThat(result.hunks().get(0).oldStart()).isEqualTo(1); // 앞 컨텍스트(최대 3줄)
	}

	@Test
	void identicalCodeHasNoHunksAndDistantChangesSplitIntoHunks() {
		assertThat(LineDiff.diff("x\ny\n", "x\ny\n").hunks()).isEmpty();
		StringBuilder oldCode = new StringBuilder();
		StringBuilder newCode = new StringBuilder();
		for (int i = 1; i <= 40; i++) {
			oldCode.append("line").append(i).append('\n');
			newCode.append(i == 3 || i == 38 ? "CHANGED" + i : "line" + i).append('\n');
		}
		var result = LineDiff.diff(oldCode.toString(), newCode.toString());
		assertThat(result.hunks()).hasSize(2);
		assertThat(result.hunks().get(1).oldStart()).isEqualTo(35);
	}

	@Test
	void lineEndingsAndTrailingNewlineAreVisibleDifferences() {
		assertThat(LineDiff.diff("a\nb", "a\nb\n").added()).isEqualTo(1); // 마지막 줄바꿈 유무
		assertThat(LineDiff.diff("a\r\nb\r\n", "a\nb\n").deleted()).isEqualTo(2); // CRLF vs LF
		assertThat(LineDiff.diff("", "a\n").added()).isEqualTo(1);
		assertThat(LineDiff.diff("a\n", "").deleted()).isEqualTo(1);
		var noEol = LineDiff.diff("a", "b").hunks().get(0).lines();
		assertThat(noEol).allMatch(Line::noEol);
	}

	@Test
	void randomInputsGiveMinimalEditsAndConsistentLineNumbers() {
		Random random = new Random(7);
		for (int round = 0; round < 300; round++) {
			List<String> a = new ArrayList<>();
			List<String> b = new ArrayList<>();
			int n = random.nextInt(25);
			int m = random.nextInt(25);
			for (int i = 0; i < n; i++) a.add("l" + random.nextInt(6));
			for (int i = 0; i < m; i++) b.add("l" + random.nextInt(6));
			String x = a.isEmpty() ? "" : String.join("\n", a) + "\n";
			String y = b.isEmpty() ? "" : String.join("\n", b) + "\n";
			var result = LineDiff.diff(x, y);
			assertThat(result.added() + result.deleted()).as(x + "|" + y).isEqualTo(editDistance(a, b));
			// 모든 줄의 번호가 원문의 해당 줄과 일치한다.
			for (var hunk : result.hunks()) {
				for (Line l : hunk.lines()) {
					if (l.type() != Type.ADD) assertThat(a.get(l.oldNo() - 1)).isEqualTo(l.text());
					if (l.type() != Type.DELETE) assertThat(b.get(l.newNo() - 1)).isEqualTo(l.text());
				}
			}
		}
	}

	@Test
	void hugeEditDistanceIsRefusedQuicklyInsteadOfHoldingTheServer() {
		StringBuilder a = new StringBuilder();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			a.append("old").append(i).append('\n');
			b.append("new").append(i).append('\n');
		}
		long start = System.nanoTime();
		assertThatThrownBy(() -> LineDiff.diff(a.toString(), b.toString())).isInstanceOf(LineDiff.TooLargeException.class);
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
	}
}
