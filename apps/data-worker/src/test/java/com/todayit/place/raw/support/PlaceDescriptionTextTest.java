package com.todayit.place.raw.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** HTML 소개의 일반 텍스트 변환과 불필요한 내용 제거를 검증합니다. */
class PlaceDescriptionTextTest {

  /** HTML 태그와 엔티티를 줄바꿈이 보존된 일반 텍스트로 변환하는지 검증합니다. */
  @Test
  void convertsMarkupAndEntitiesToTextWithLineBreaks() {
    assertThat(PlaceDescriptionText.fromHtml("<p>서울&nbsp;공원 &amp; 산책</p><p>둘째<br>셋째</p>"))
        .isEqualTo("서울 공원 & 산책\n\n둘째\n셋째");
  }

  /** 스크립트·스타일·주석과 링크 속성이 소개에 포함되지 않는지 검증합니다. */
  @Test
  void omitsScriptsStylesCommentsAndLinkAttributes() {
    String html =
        "<html><head><style>body { color:red }</style><script>alert('secret')</script></head>"
            + "<body><!-- comment --><a href='https://example.com/private'>안내</a><script>alert('hidden')</script></body></html>";

    assertThat(PlaceDescriptionText.fromHtml(html)).isEqualTo("안내");
  }

  /**
   * 빈 소개를 임의의 문장 없이 누락 값으로 유지하는지 검증합니다.
   *
   * @param value 검증할 필드 값
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "<p> </p>", "<br>", "<!-- hidden -->"})
  void emptyDescriptionRemainsAbsent(String value) {
    assertThat(PlaceDescriptionText.fromHtml(value)).isNull();
  }

  /** 이스케이프된 꺾쇠를 일반 텍스트로 보존하며 안전한 HTML로 해석하지 않는지 검증합니다. */
  @Test
  void preservesLiteralEscapedAngleBracketsAsTextNotSafeHtml() {
    assertThat(PlaceDescriptionText.fromHtml("&lt;안내&gt; 텍스트")).isEqualTo("<안내> 텍스트");
  }
}
