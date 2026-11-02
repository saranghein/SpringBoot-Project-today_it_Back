package com.todayit.common.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.todayit.common.file.config.FileUploadProperties;
import com.todayit.common.file.entity.FileRelatedType;
import com.todayit.common.file.exception.FileErrorCode;
import com.todayit.common.file.exception.FileException;
import com.todayit.common.file.service.model.ValidatedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

@DisplayName("파일 검증 서비스")
class FileValidationServiceTest {
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=");
  private final FileValidationService validation = validation(10_485_760, 5, 31_457_280L);

  @Test
  @DisplayName("확장자 대소문자와 관계없이 MIME과 실제 형식이 같은 이미지를 허용한다")
  void acceptsUppercaseExtensionWithMatchingBytesAndMime() {
    // given
    List<MultipartFile> files = List.of(file("photo.PNG", "image/png", PNG));

    // when
    List<ValidatedFile> result = validation.validate(FileRelatedType.MEMBER_PROFILE, files);

    // then
    assertThat(result.getFirst().extension().value()).isEqualTo("png");
    assertThat(result.getFirst().content()).isEqualTo(PNG);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidFiles")
  @DisplayName("유효하지 않은 파일은 원인에 맞는 커스텀 오류로 거부한다")
  void rejectsInvalidFile(
      String description, String name, String mime, byte[] content, FileErrorCode expected) {
    // given
    List<MultipartFile> files = List.of(file(name, mime, content));

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.FAQ_ATTACHMENT, files));

    // then
    assertFileError(thrown, expected);
  }

  @Test
  @DisplayName("파일 크기가 정확히 10 MiB이면 허용한다")
  void acceptsExactlyTenMib() {
    // given
    List<MultipartFile> files =
        List.of(file("photo.png", "image/png", Arrays.copyOf(PNG, 10_485_760)));

    // when
    List<ValidatedFile> result = validation.validate(FileRelatedType.FAQ_ATTACHMENT, files);

    // then
    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("10 MiB를 1바이트 초과하는 파일은 거부한다")
  void rejectsOneByteOverTenMib() {
    // given
    List<MultipartFile> files =
        List.of(file("photo.png", "image/png", Arrays.copyOf(PNG, 10_485_761)));

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.FAQ_ATTACHMENT, files));

    // then
    assertFileError(thrown, FileErrorCode.INVALID_SIZE);
  }

  @Test
  @DisplayName("문의 첨부 합계가 정확히 30 MiB이면 허용한다")
  void acceptsExactlyThirtyMibForInquiry() {
    // given
    MockMultipartFile large = file("photo.png", "image/png", Arrays.copyOf(PNG, 10_485_760));
    List<MultipartFile> files = List.of(large, large, large);

    // when
    List<ValidatedFile> result = validation.validate(FileRelatedType.INQUIRY_ATTACHMENT, files);

    // then
    assertThat(result).hasSize(3);
  }

  @Test
  @DisplayName("문의 첨부 합계가 30 MiB를 초과하면 거부한다")
  void rejectsInquiryTotalOverThirtyMib() {
    // given
    MockMultipartFile large = file("photo.png", "image/png", Arrays.copyOf(PNG, 10_485_760));
    List<MultipartFile> files = List.of(large, large, large, file("photo.png", "image/png", PNG));

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.INQUIRY_ATTACHMENT, files));

    // then
    assertFileError(thrown, FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
  }

  @Test
  @DisplayName("문의 첨부파일은 정확히 5개까지 허용한다")
  void acceptsFiveInquiryFiles() {
    // given
    List<MultipartFile> files = Collections.nCopies(5, file("photo.png", "image/png", PNG));

    // when
    List<ValidatedFile> result = validation.validate(FileRelatedType.INQUIRY_ATTACHMENT, files);

    // then
    assertThat(result).hasSize(5);
  }

  @Test
  @DisplayName("문의 첨부파일이 6개이면 거부한다")
  void rejectsSixInquiryFiles() {
    // given
    List<MultipartFile> files = Collections.nCopies(6, file("photo.png", "image/png", PNG));

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.INQUIRY_ATTACHMENT, files));

    // then
    assertFileError(thrown, FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
  }

  @Test
  @DisplayName("문의 첨부에는 PDF를 허용한다")
  void acceptsPdfForAttachments() {
    // given
    List<MultipartFile> files =
        List.of(
            file(
                "document.pdf",
                "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII)));

    // when
    List<ValidatedFile> result = validation.validate(FileRelatedType.INQUIRY_ATTACHMENT, files);

    // then
    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("프로필 이미지로 PDF를 업로드하면 거부한다")
  void rejectsPdfForProfile() {
    // given
    List<MultipartFile> files =
        List.of(
            file(
                "document.pdf",
                "application/pdf",
                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII)));

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.MEMBER_PROFILE, files));

    // then
    assertFileError(thrown, FileErrorCode.PROFILE_IMAGE_REQUIRED);
  }

  @Test
  @DisplayName("파일 스트림 읽기 실패는 내부 메시지 없이 파일 읽기 오류로 변환한다")
  void convertsFileReadFailure() throws IOException {
    // given
    MockMultipartFile unreadable = spy(file("photo.png", "image/png", PNG));
    doThrow(new IOException("internal-stream-detail")).when(unreadable).getInputStream();
    List<MultipartFile> files = List.of(unreadable);

    // when
    Throwable thrown =
        catchThrowable(() -> validation.validate(FileRelatedType.FAQ_ATTACHMENT, files));

    // then
    assertFileError(thrown, FileErrorCode.READ_FAILED);
    assertThat(thrown).hasNoCause().hasMessageNotContaining("internal-stream-detail");
  }

  @Test
  @DisplayName("multipart 설정의 파일 크기 제한을 변경하면 검증에도 적용한다")
  void appliesConfiguredMultipartFileSize() {
    // given
    FileValidationService configured = validation(PNG.length, 5, 31_457_280L);

    // when
    List<MultipartFile> accepted = List.of(file("photo.png", "image/png", PNG));
    List<MultipartFile> oversized =
        List.of(file("photo.png", "image/png", Arrays.copyOf(PNG, PNG.length + 1)));

    // then
    assertThat(configured.validate(FileRelatedType.FAQ_ATTACHMENT, accepted)).hasSize(1);
    assertFileError(
        catchThrowable(() -> configured.validate(FileRelatedType.FAQ_ATTACHMENT, oversized)),
        FileErrorCode.INVALID_SIZE);
  }

  @Test
  @DisplayName("설정한 문의 첨부 개수와 합계 용량을 각각 적용한다")
  void appliesConfiguredInquiryLimits() {
    // given
    FileValidationService countLimited = validation(PNG.length, 1, PNG.length * 3L);
    FileValidationService sizeLimited = validation(PNG.length, 3, PNG.length * 2L);
    MultipartFile image = file("photo.png", "image/png", PNG);

    // then
    assertThat(countLimited.validate(FileRelatedType.INQUIRY_ATTACHMENT, List.of(image)))
        .hasSize(1);
    assertFileError(
        catchThrowable(
            () -> countLimited.validate(FileRelatedType.INQUIRY_ATTACHMENT, List.of(image, image))),
        FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
    assertThat(sizeLimited.validate(FileRelatedType.INQUIRY_ATTACHMENT, List.of(image, image)))
        .hasSize(2);
    assertFileError(
        catchThrowable(
            () ->
                sizeLimited.validate(
                    FileRelatedType.INQUIRY_ATTACHMENT, List.of(image, image, image))),
        FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
  }

  private FileValidationService validation(long maxFileBytes, int maxFiles, long maxTotalBytes) {
    MultipartProperties multipart = new MultipartProperties();
    multipart.setMaxFileSize(DataSize.ofBytes(maxFileBytes));
    return new FileValidationService(
        multipart, new FileUploadProperties(maxFiles, DataSize.ofBytes(maxTotalBytes)));
  }

  private static Stream<Arguments> invalidFiles() {
    return Stream.of(
        Arguments.of("선언 MIME 불일치", "photo.png", "image/jpeg", PNG, FileErrorCode.MIME_MISMATCH),
        Arguments.of(
            "실제 형식 불일치",
            "photo.png",
            "image/png",
            "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
            FileErrorCode.CONTENT_MISMATCH),
        Arguments.of(
            "허용하지 않은 확장자", "photo.svg", "image/svg+xml", PNG, FileErrorCode.UNSUPPORTED_EXTENSION),
        Arguments.of("확장자 없음", "photo", "image/png", PNG, FileErrorCode.UNSUPPORTED_EXTENSION),
        Arguments.of("경로 포함", "../photo.png", "image/png", PNG, FileErrorCode.INVALID_NAME),
        Arguments.of("제어 문자 포함", "photo\n.png", "image/png", PNG, FileErrorCode.INVALID_NAME),
        Arguments.of("빈 파일", "photo.png", "image/png", new byte[0], FileErrorCode.INVALID_SIZE));
  }

  private void assertFileError(Throwable thrown, FileErrorCode expected) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FileException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
  }

  private MockMultipartFile file(String name, String mime, byte[] content) {
    return new MockMultipartFile("files", name, mime, content);
  }
}
