package com.todayit.common.file.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.todayit.common.file.config.CloudinaryProperties;
import com.todayit.common.file.entity.FileRelatedType;
import com.todayit.common.file.exception.FileErrorCode;
import com.todayit.common.file.exception.FileStorageException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@DisplayName("Cloudinary 파일 저장소")
class CloudinaryFileStorageTest {
  private final CloudinaryProperties properties =
      new CloudinaryProperties("test-cloud", "test-key", "test-secret", Duration.ofSeconds(1));

  @Test
  @DisplayName("PDF를 raw 엔드포인트로 업로드하고 반환된 파일 식별자를 확인한다")
  void uploadsPdfAsRawAndChecksReturnedIdentity() {
    // given
    AtomicReference<ClientRequest> request = new AtomicReference<>();
    CloudinaryFileStorage client =
        storage(
            HttpStatus.OK,
            "{\"public_id\":\"inquiry/key.pdf\",\"resource_type\":\"raw\",\"type\":\"authenticated\",\"bytes\":3}",
            request);
    // when
    client.upload(
        "inquiry/key.pdf", "application/pdf", FileRelatedType.INQUIRY_ATTACHMENT, new byte[3]);
    // then
    assertThat(request.get().url().getPath()).isEqualTo("/v1_1/test-cloud/raw/upload");
    assertThat(request.get().headers().getFirst("Authorization")).startsWith("Basic ");
    assertThat(requestBody(request.get()))
        .contains("name=\"asset_folder\"")
        .contains("\r\n\r\npdf\r\n");
  }

  @Test
  @DisplayName("이미지는 image 폴더를 지정하여 업로드한다")
  void uploadsImageToImageFolder() {
    // given
    AtomicReference<ClientRequest> request = new AtomicReference<>();
    CloudinaryFileStorage client =
        storage(
            HttpStatus.OK,
            "{\"public_id\":\"image/key\",\"resource_type\":\"image\",\"type\":\"upload\",\"bytes\":3}",
            request);

    // when
    client.upload("image/key", "image/png", FileRelatedType.MEMBER_PROFILE, new byte[3]);

    // then
    assertThat(requestBody(request.get()))
        .contains("name=\"asset_folder\"")
        .contains("\r\n\r\nimage\r\n");
  }

  @Test
  @DisplayName("제공자 오류는 내부 응답 없이 커스텀 저장소 오류로 변환한다")
  void doesNotExposeProviderErrorBody() {
    // given
    CloudinaryFileStorage client =
        storage(HttpStatus.UNAUTHORIZED, "secret-provider-body", new AtomicReference<>());

    // when
    Throwable thrown =
        catchThrowable(
            () -> client.upload("key", "image/png", FileRelatedType.MEMBER_PROFILE, new byte[3]));

    // then
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FileStorageException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.STORAGE_FAILED))
        .hasMessage("파일 저장소 작업에 실패했습니다.")
        .hasNoCause();
  }

  @Test
  @DisplayName("이미 삭제된 파일을 다시 삭제해도 성공한다")
  void deletionIsIdempotentWhenAssetIsAlreadyGone() {
    // given
    CloudinaryFileStorage client =
        storage(HttpStatus.OK, "{\"result\":\"not found\"}", new AtomicReference<>());

    // when
    Throwable thrown =
        catchThrowable(() -> client.delete("key", "image/png", FileRelatedType.MEMBER_PROFILE));

    // then
    assertThat(thrown).isNull();
  }

  @Test
  @DisplayName("프로필은 공개 URL을 생성하고 문의 첨부는 만료 시간 없는 서명 URL을 생성한다")
  void generatesPublicProfileAndSignedInquiryUrlsWithoutExpiration() {
    // given
    CloudinaryFileStorage client = new CloudinaryFileStorage(properties);

    // when
    String profileUrl =
        client.accessUrl("member_profile/key", "image/png", FileRelatedType.MEMBER_PROFILE);
    String pdfUrl =
        client.accessUrl("inquiry/key.pdf", "application/pdf", FileRelatedType.INQUIRY_ATTACHMENT);
    String jpegUrl =
        client.accessUrl("inquiry/key", "image/jpeg", FileRelatedType.INQUIRY_ATTACHMENT);

    // then
    assertThat(profileUrl)
        .isEqualTo("https://res.cloudinary.com/test-cloud/image/upload/v1/member_profile/key");
    assertThat(pdfUrl)
        .isEqualTo(
            "https://res.cloudinary.com/test-cloud/raw/authenticated/s--Ptiep44D--/inquiry/key.pdf");
    assertThat(jpegUrl)
        .isEqualTo(
            "https://res.cloudinary.com/test-cloud/image/authenticated/s--K_MMGbjN--/inquiry/key");
    assertThat(pdfUrl).doesNotContain("expires_at", "timestamp", "test-key", "test-secret");
    assertThat(jpegUrl)
        .isEqualTo(
            client.accessUrl("inquiry/key", "image/jpeg", FileRelatedType.INQUIRY_ATTACHMENT));
    assertThat(properties.toString()).doesNotContain("test-secret", "test-key");
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid-json", "{}"})
  @DisplayName("잘못된 제공자 응답은 커스텀 저장소 오류로 변환한다")
  void convertsInvalidProviderResponse(String body) {
    // given
    CloudinaryFileStorage client = storage(HttpStatus.OK, body, new AtomicReference<>());

    // when
    Throwable thrown =
        catchThrowable(
            () -> client.upload("key", "image/png", FileRelatedType.MEMBER_PROFILE, new byte[3]));

    // then
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FileStorageException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.STORAGE_FAILED));
  }

  @Test
  @DisplayName("제공자 응답 시간이 초과하면 커스텀 저장소 오류로 변환한다")
  void convertsProviderTimeout() {
    // given
    CloudinaryProperties shortTimeout =
        new CloudinaryProperties("test-cloud", "test-key", "test-secret", Duration.ofMillis(20));
    WebClient web =
        WebClient.builder()
            .baseUrl("https://api.cloudinary.com")
            .exchangeFunction(request -> Mono.never())
            .build();
    CloudinaryFileStorage client = new CloudinaryFileStorage(shortTimeout, web);

    // when
    Throwable thrown =
        catchThrowable(
            () -> client.upload("key", "image/png", FileRelatedType.MEMBER_PROFILE, new byte[3]));

    // then
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FileStorageException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.STORAGE_FAILED));
  }

  private String requestBody(ClientRequest request) {
    MockClientHttpRequest output = new MockClientHttpRequest(HttpMethod.POST, request.url());
    request
        .body()
        .insert(
            output,
            new BodyInserter.Context() {
              @Override
              public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
              }

              @Override
              public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
              }

              @Override
              public Map<String, Object> hints() {
                return Map.of();
              }
            })
        .block();
    return output.getBodyAsString().block();
  }

  private CloudinaryFileStorage storage(
      HttpStatus status, String body, AtomicReference<ClientRequest> request) {
    WebClient web =
        WebClient.builder()
            .baseUrl("https://api.cloudinary.com")
            .exchangeFunction(
                call -> {
                  request.set(call);
                  return Mono.just(
                      ClientResponse.create(status)
                          .header("Content-Type", "application/json")
                          .body(body)
                          .build());
                })
            .build();
    return new CloudinaryFileStorage(properties, web);
  }
}
