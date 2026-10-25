package com.todayit.place.client.external.support;

import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 외부 요청의 timeout·키 인코딩·오류 비식별화 및 JSON/XML 파싱을 담당합니다. */
public final class PlaceSourceHttpClient {

  private final WebClient client;
  private final String baseUrl;
  private final String serviceKey;
  private final Duration timeout;
  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * 공급원별 고정 주소와 인증 정보를 받습니다. baseUrl은 운영 설정에서만 주입합니다.
   *
   * @param client HTTP Client, redirect를 따라가지 않는 설정 사용
   * @param baseUrl 공급원 기본 주소
   * @param serviceKey URL 인코딩 전 발급키
   * @param timeout 전체 응답 제한 시간
   * @throws IllegalArgumentException 제한 시간이 양수가 아닐 때
   */
  public PlaceSourceHttpClient(
      WebClient client, String baseUrl, String serviceKey, Duration timeout) {
    this.client = Objects.requireNonNull(client);
    this.baseUrl = Objects.requireNonNull(baseUrl);
    this.serviceKey = Objects.requireNonNull(serviceKey);
    this.timeout = Objects.requireNonNull(timeout);
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout은 양수여야 합니다.");
    }
  }

  /**
   * 한 번만 요청하고 검증된 JSON 트리를 반환합니다. XML은 필드 구조를 보존해 변환합니다.
   *
   * @param path 구현체에서 정한 상대 경로
   * @param parameters 검증한 비밀값 없는 파라미터
   * @return 성공 HTTP 응답의 파싱 결과
   * @throws PlaceSourceException 인증·전송·파싱 실패 시
   */
  public JsonNode get(String path, Map<String, String> parameters) {
    if (serviceKey.isBlank()) {
      throw new PlaceSourceException(Reason.AUTHENTICATION);
    }
    try {
      var builder = UriComponentsBuilder.fromUriString(baseUrl).path(path);
      Map<String, Object> variables = new LinkedHashMap<>();
      int index = 0;
      for (var entry : parameters.entrySet()) {
        String variable = "p" + index++;
        builder.queryParam(entry.getKey(), "{" + variable + "}");
        variables.put(variable, entry.getValue());
      }
      builder.queryParam("serviceKey", "{credential}");
      variables.put("credential", serviceKey);
      var uri = builder.encode().buildAndExpand(variables).toUri();
      String body =
          client
              .get()
              .uri(uri)
              .exchangeToMono(
                  response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                      return response.bodyToMono(String.class);
                    }
                    Reason reason =
                        status == 401 || status == 403
                            ? Reason.AUTHENTICATION
                            : status == 429
                                ? Reason.RATE_LIMIT
                                : status >= 500 ? Reason.TRANSIENT : Reason.INVALID_REQUEST;
                    return response
                        .releaseBody()
                        .then(Mono.error(new PlaceSourceException(reason)));
                  })
              .timeout(timeout)
              .block();
      if (body == null || body.isBlank()) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      JsonNode parsed = parse(body);
      JsonNode gateway = parsed.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
      if (!gateway.isMissingNode()) {
        checkCode(gateway.path("returnReasonCode").asText());
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      return parsed;
    } catch (PlaceSourceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      // WebClient 예외는 인증키가 포함된 URI를 메시지에 가질 수 있습니다.
      if (NestedExceptionUtils.getMostSpecificCause(exception)
          instanceof DataBufferLimitException) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      throw new PlaceSourceException(Reason.TRANSIENT);
    }
  }

  /**
   * 성공 코드와 공공 API 업무 오류를 구분합니다.
   *
   * @param code 외부 결과 코드
   * @throws PlaceSourceException 성공이 아닌 코드일 때
   */
  public static void checkCode(String code) {
    switch (code) {
      case "00", "0000", "0" -> {}
      case "20", "020", "30", "030", "31", "031", "32", "032", "29" ->
          throw new PlaceSourceException(Reason.AUTHENTICATION);
      case "22", "022", "23", "023" -> throw new PlaceSourceException(Reason.RATE_LIMIT);
      case "01", "1", "001", "04", "4", "05", "5", "99" ->
          throw new PlaceSourceException(Reason.TRANSIENT);
      case "10", "12", "012" -> throw new PlaceSourceException(Reason.INVALID_REQUEST);
      default -> throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
  }

  /**
   * JSON 또는 XML 응답을 트리로 읽고 파싱 오류의 원문과 cause를 외부에 전달하지 않습니다. XML의 DTD·외부 Entity·외부 스키마 접근은 차단합니다.
   *
   * @param body 성공 HTTP 응답 본문
   * @return JSON 트리; XML은 루트 요소 이름을 포함하는 JSON 표현
   * @throws PlaceSourceException 파싱이나 XML 보안 설정에 실패할 때
   */
  private JsonNode parse(String body) {
    try {
      if (!body.stripLeading().startsWith("<")) {
        return mapper.readTree(body);
      }
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var parser = factory.newDocumentBuilder();
      parser.setErrorHandler(new DefaultHandler());
      Element root = parser.parse(new InputSource(new StringReader(body))).getDocumentElement();
      return mapper.valueToTree(Map.of(root.getTagName(), xmlValue(root)));
    } catch (Exception exception) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
  }

  /**
   * XML의 반복 요소는 목록으로, 단일 요소는 값으로 변환합니다. 말단 값은 숫자로 해석하지 않아 식별자의 선행 0을 보존하며, 속성이 아닌 요소와 텍스트를 수집 대상으로
   * 삼습니다.
   *
   * @param element 변환할 XML 요소
   * @return 말단 텍스트 또는 자식 요소 이름별 값·목록을 담은 Map
   */
  private Object xmlValue(Element element) {
    Map<String, List<Object>> children = new LinkedHashMap<>();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element nested) {
        children
            .computeIfAbsent(nested.getTagName(), ignored -> new ArrayList<>())
            .add(xmlValue(nested));
      }
    }
    if (children.isEmpty()) {
      return element.getTextContent();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    children.forEach(
        (key, values) -> result.put(key, values.size() == 1 ? values.getFirst() : values));
    return result;
  }
}
