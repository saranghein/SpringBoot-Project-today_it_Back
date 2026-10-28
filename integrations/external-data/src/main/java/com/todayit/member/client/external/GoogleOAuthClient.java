package com.todayit.member.client.external;

import com.nimbusds.jose.RemoteKeySourceException;
import com.todayit.member.client.external.GoogleOAuthException.Reason;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

/** Google OAuth 인가 코드 교환과 ID Token 검증을 담당합니다. */
public class GoogleOAuthClient {

  private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

  private static final String JWK_SET_URL = "https://www.googleapis.com/oauth2/v3/certs";

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final WebClient webClient;
  private final JwtDecoder jwtDecoder;
  private final String clientId;
  private final String clientSecret;
  private final String redirectUri;

  /**
   * Google OAuth 클라이언트를 생성합니다.
   *
   * @param webClientBuilder HTTP 클라이언트 빌더
   * @param clientId Google OAuth Client ID
   * @param clientSecret Google OAuth Client Secret
   * @param redirectUri 서버에 등록된 Redirect URI
   */
  public GoogleOAuthClient(
      WebClient.Builder webClientBuilder,
      String clientId,
      String clientSecret,
      String redirectUri) {

    // 운영 환경에서는 Google 공개키를 사용하는 JWT 검증기 생성
    this(webClientBuilder, clientId, clientSecret, redirectUri, createJwtDecoder());
  }

  /**
   * 테스트에서 JWT 검증기를 주입할 수 있도록 제공하는 생성자입니다.
   *
   * <p>패키지 내부에서만 사용할 수 있습니다.
   */
  GoogleOAuthClient(
      WebClient.Builder webClientBuilder,
      String clientId,
      String clientSecret,
      String redirectUri,
      JwtDecoder jwtDecoder) {

    this.webClient = webClientBuilder.build();
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.redirectUri = redirectUri;
    this.jwtDecoder = jwtDecoder;
  }

  /**
   * Google 공개키를 사용해 ID Token을 검증할 JWT Decoder를 생성합니다.
   *
   * @return Google 공개키 기반 JWT 검증기
   */
  private static JwtDecoder createJwtDecoder() {

    // Google 공개키 서버의 연결 및 응답 대기 시간 제한
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(REQUEST_TIMEOUT);

    return NimbusJwtDecoder.withJwkSetUri(JWK_SET_URL)
        .restOperations(new RestTemplate(requestFactory))
        .build();
  }

  /**
   * Google 인가 코드를 교환하고 사용자 정보를 검증합니다.
   *
   * @param authorizationCode Google 인가 코드
   * @param requestedRedirectUri 인증 요청에 사용한 Redirect URI
   * @return 검증된 Google 사용자 정보
   * @throws GoogleOAuthException Google 인증에 실패하거나 토큰이 유효하지 않은 경우
   * @throws IllegalStateException Google OAuth 환경변수가 설정되지 않은 경우
   */
  public GoogleIdentity authenticate(String authorizationCode, String requestedRedirectUri) {

    if (clientId.isBlank() || clientSecret.isBlank()) {
      throw new IllegalStateException("Google OAuth 설정이 없습니다.");
    }

    if (authorizationCode == null || authorizationCode.isBlank()) {
      throw new GoogleOAuthException(Reason.INVALID_AUTHORIZATION_CODE);
    }

    // Google Cloud에 등록한 URI와 요청 URI가 동일해야 함
    if (!redirectUri.equals(requestedRedirectUri)) {
      throw new GoogleOAuthException(Reason.INVALID_AUTHORIZATION_CODE);
    }

    String idToken = exchangeCode(authorizationCode);

    Jwt jwt;

    try {
      // Google 공개키를 이용해 ID Token의 서명 및 유효 시간 검증
      jwt = jwtDecoder.decode(idToken);

    } catch (JwtException exception) {
      // Google 공개키 조회 실패라면 외부 서비스 장애로 처리
      if (isJwkSetRetrievalFailure(exception)) {
        throw new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE, exception);
      }

      // 서명 오류, 토큰 만료 등은 기존과 동일하게 인증 실패 처리
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN, exception);
    }

    // Google issuer는 scheme이 없는 문자열도 허용하므로 문자열로 조회
    String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);

    // Google 발급자 확인
    if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    // 토큰을 사용하도록 지정된 애플리케이션
    // 현재 Today-it OAuth Client에 발급된 토큰인지 확인
    List<String> audiences = jwt.getAudience();

    if (audiences == null || !audiences.contains(clientId)) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    String authorizedParty = jwt.getClaimAsString("azp");

    if (authorizedParty != null && !clientId.equals(authorizedParty)) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    // 토큰 만료 시각, 발급 시각 확인
    if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    // google 사용자 정보 추출 -> DB provider_user_id로 사용
    String providerUserId = jwt.getSubject();

    if (providerUserId == null || providerUserId.isBlank() || providerUserId.length() > 255) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    // 검증된 Google 고유 ID만 회원 식별에 사용
    return new GoogleIdentity(providerUserId, null, false);
  }

  /**
   * JWT 검증 실패의 원인이 Google 공개키 조회 오류인지 확인합니다.
   *
   * @param exception JWT 검증 과정에서 발생한 예외
   * @return 공개키 조회 실패이면 true
   */
  static boolean isJwkSetRetrievalFailure(Throwable exception) {
    // Spring Security가 예외를 여러 단계로 감싸 전달할 수 있음
    Throwable cause = exception;

    while (cause != null) {

      // Google 공개키를 가져오는 과정에서 발생한 오류
      if (cause instanceof RemoteKeySourceException) {
        return true;
      }

      // 가장 안쪽 원인 예외까지 확인
      cause = cause.getCause();
    }

    return false;
  }

  /**
   * Google 인가 코드를 ID Token으로 교환합니다.
   *
   * <p>WebClient의 오류 처리 기능을 사용해 HTTP 오류, 네트워크 장애 및 응답 시간 초과를 Google OAuth 커스텀 예외로 변환합니다.
   *
   * @param authorizationCode Google 인가 코드
   * @return Google ID Token
   * @throws GoogleOAuthException 인가 코드가 유효하지 않거나 Google 서버 통신에 실패한 경우
   */
  private String exchangeCode(String authorizationCode) {

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

    form.add("code", authorizationCode);
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("redirect_uri", redirectUri);
    form.add("grant_type", "authorization_code");

    // 인가 코드를 Google 토큰 발급 API에 전달
    Map<String, Object> response =
        webClient
            .post()
            .uri(TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()

            // Google 요청 제한(429)은 인가 코드 오류가 아닌 외부 서비스 장애로 처리
            .onStatus(
                status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(),
                result -> Mono.error(new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE)))

            // 그 외 Google 4xx 응답은 인증 실패로 처리
            .onStatus(
                HttpStatusCode::is4xxClientError,
                result -> Mono.error(new GoogleOAuthException(Reason.INVALID_AUTHORIZATION_CODE)))

            // Google 서버의 5xx 응답은 외부 서버 장애로 처리
            .onStatus(
                HttpStatusCode::is5xxServerError,
                result -> Mono.error(new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE)))

            // Google 응답을 Map 형태로 변환
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})

            // Google 응답을 기다리는 시간을 5초로 제한
            .timeout(REQUEST_TIMEOUT)

            // WebClient의 네트워크 및 HTTP 처리 오류를 커스텀 예외로 변환
            .onErrorMap(
                WebClientException.class,
                exception -> new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE, exception))

            // Google 응답 데이터의 역직렬화 오류 처리
            .onErrorMap(
                CodecException.class,
                exception -> new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE, exception))

            // Google 서버 응답 시간 초과 처리
            .onErrorMap(
                TimeoutException.class,
                exception -> new GoogleOAuthException(Reason.PROVIDER_UNAVAILABLE, exception))

            // Spring MVC 환경이므로 응답 결과를 동기적으로 가져옴
            .block();

    // 응답에 ID Token이 없거나 비어 있다면 인증 실패 처리
    if (response == null
        || !(response.get("id_token") instanceof String idToken)
        || idToken.isBlank()) {
      throw new GoogleOAuthException(Reason.INVALID_ID_TOKEN);
    }

    return idToken;
  }
}
