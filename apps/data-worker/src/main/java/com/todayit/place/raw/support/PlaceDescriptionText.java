package com.todayit.place.raw.support;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** 소개 HTML을 표시용 일반 텍스트로 바꿉니다. HTML 출력용 보안 sanitizer로 사용하지 않습니다. */
public final class PlaceDescriptionText {

  private static final Set<HTML.Tag> BLOCKS =
      Set.of(
          HTML.Tag.P,
          HTML.Tag.DIV,
          HTML.Tag.LI,
          HTML.Tag.UL,
          HTML.Tag.OL,
          HTML.Tag.H1,
          HTML.Tag.H2,
          HTML.Tag.H3,
          HTML.Tag.TR);

  private PlaceDescriptionText() {}

  /**
   * 태그와 script/style 내용을 제거하고 줄바꿈·HTML 문자 참조를 일반 텍스트로 변환합니다.
   *
   * @param html 원천 소개
   * @return 정리한 소개, 없거나 표시할 내용이 없으면 null
   * @throws IllegalStateException 메모리 Reader 처리 중 예기치 않은 I/O 오류가 발생할 때
   */
  public static String fromHtml(String html) {
    if (html == null || html.isBlank()) {
      return null;
    }
    StringBuilder result = new StringBuilder();
    var callback =
        new HTMLEditorKit.ParserCallback() {
          private int suppressed;

          @Override
          public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
              suppressed++;
            } else if (suppressed == 0 && BLOCKS.contains(tag)) {
              result.append('\n');
            }
          }

          @Override
          public void handleEndTag(HTML.Tag tag, int position) {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
              suppressed = Math.max(0, suppressed - 1);
            } else if (suppressed == 0 && BLOCKS.contains(tag)) {
              result.append('\n');
            }
          }

          @Override
          public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (suppressed == 0 && (tag == HTML.Tag.BR || tag == HTML.Tag.HR)) {
              result.append('\n');
            }
          }

          @Override
          public void handleText(char[] text, int position) {
            if (suppressed == 0) {
              result.append(text);
            }
          }
        };
    try {
      new ParserDelegator().parse(new StringReader(html), callback, true);
    } catch (IOException exception) {
      throw new IllegalStateException("소개 텍스트 변환 실패");
    }
    String value =
        result
            .toString()
            .replace('\u00a0', ' ')
            .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
            .replaceAll(" *\\n *", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .strip();
    return value.isEmpty() ? null : value;
  }
}
