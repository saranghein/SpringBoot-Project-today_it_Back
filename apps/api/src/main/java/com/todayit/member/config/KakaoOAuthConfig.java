package com.todayit.member.config;

import com.todayit.member.client.external.KakaoOAuthClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** API 서버에서 사용하는 Kakao OAuth 연동 설정입니다. */
@Configuration(proxyBeanMethods = false)
public class KakaoOAuthConfig {
  /**
   * Kakao OAuth 클라이언트를 등록합니다.
   *
   * @param clientId Kakao REST API 키
   * @param clientSecret Kakao Client Secret
   * @param redirectUri 등록된 Redirect URI
   * @return Kakao OAuth 클라이언트
   */
  @Bean
  public KakaoOAuthClient kakaoOAuthClient(
      @Value("${todayit.oauth.kakao.client-id:}") String clientId,
      @Value("${todayit.oauth.kakao.client-secret:}") String clientSecret,
      @Value("${todayit.oauth.kakao.redirect-uri}") String redirectUri) {

    return new KakaoOAuthClient(WebClient.builder(), clientId, clientSecret, redirectUri);
  }
}
