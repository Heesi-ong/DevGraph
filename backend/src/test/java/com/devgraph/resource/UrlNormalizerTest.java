package com.devgraph.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.devgraph.resource.domain.UrlNormalizer;

/** 설계서 §12.3 `url_normalized` 규칙 5가지와 경계. */
class UrlNormalizerTest {

	@Test
	void lowercasesSchemeAndHostAndDropsDefaultPorts() {
		assertThat(UrlNormalizer.normalize("HTTPS://Example.COM/Path")).isEqualTo("https://example.com/Path");
		assertThat(UrlNormalizer.normalize("https://example.com:443/a")).isEqualTo("https://example.com/a");
		assertThat(UrlNormalizer.normalize("http://example.com:80/a")).isEqualTo("http://example.com/a");
		// 기본 포트는 scheme별이다: https의 80, http의 443은 기본이 아니다.
		assertThat(UrlNormalizer.normalize("https://example.com:80/a")).isEqualTo("https://example.com:80/a");
		assertThat(UrlNormalizer.normalize("http://example.com:8080/a")).isEqualTo("http://example.com:8080/a");
	}

	@Test
	void removesTrailingSlashesButKeepsTheRootAndTreatsEmptyPathAsRoot() {
		assertThat(UrlNormalizer.normalize("https://example.com/docs/")).isEqualTo("https://example.com/docs");
		assertThat(UrlNormalizer.normalize("https://example.com/docs//")).isEqualTo("https://example.com/docs");
		assertThat(UrlNormalizer.normalize("https://example.com/")).isEqualTo("https://example.com/");
		assertThat(UrlNormalizer.normalize("https://example.com")).isEqualTo("https://example.com/");
	}

	@Test
	void dropsFragmentsAndTrackingParametersAndSortsTheRest() {
		assertThat(UrlNormalizer.normalize("https://example.com/a?b=2&a=1#section")).isEqualTo("https://example.com/a?a=1&b=2");
		assertThat(UrlNormalizer.normalize("https://example.com/a?utm_source=x&UTM_Medium=y&ref=z&fbclid=1&gclid=2&q=keep"))
				.isEqualTo("https://example.com/a?q=keep");
		assertThat(UrlNormalizer.normalize("https://example.com/a?utm_source=x")).isEqualTo("https://example.com/a");
		// 같은 key가 여러 번이면 값 순서도 안정적으로 정렬한다.
		assertThat(UrlNormalizer.normalize("https://example.com/a?t=2&t=1")).isEqualTo("https://example.com/a?t=1&t=2");
	}

	@Test
	void doesNotDecodePathOrQueryValues() {
		assertThat(UrlNormalizer.normalize("https://example.com/a%2Fb?q=%E2%9C%93")).isEqualTo("https://example.com/a%2Fb?q=%E2%9C%93");
		// 인코딩이 다르면 다른 주소로 취급한다(같은 주소로 합치면 의미가 바뀔 수 있다).
		assertThat(UrlNormalizer.normalize("https://example.com/a%2Fb")).isNotEqualTo(UrlNormalizer.normalize("https://example.com/a/b"));
	}

	@Test
	void equivalentSpellingsNormalizeToTheSameValue() {
		String a = UrlNormalizer.normalize("HTTPS://Example.com:443/a/?utm_source=x&b=2&a=1#frag");
		String b = UrlNormalizer.normalize("https://example.com/a?a=1&b=2");
		assertThat(a).isEqualTo(b);
	}

	@Test
	void rejectsWhatIsNotAnAbsoluteUrl() {
		assertThatThrownBy(() -> UrlNormalizer.normalize("not a url")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> UrlNormalizer.normalize("/relative/path")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> UrlNormalizer.normalize("mailto:a@b.c")).isInstanceOf(IllegalArgumentException.class);
	}
}
