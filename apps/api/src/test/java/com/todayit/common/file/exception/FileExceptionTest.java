package com.todayit.common.file.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.common.exception.GlobalExceptionHandler;
import com.todayit.common.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("파일 커스텀 오류 응답")
class FileExceptionTest {
  @Test
  @DisplayName("MIME 불일치는 HTTP 400 오류 응답으로 변환한다")
  void mapsMimeMismatchToBadRequest() {
    // given
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    FileErrorCode code = FileErrorCode.MIME_MISMATCH;
    FileException exception = new FileException(code);

    // when
    ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

    // then
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code.code());
    assertThat(response.getBody().message()).isEqualTo(code.message());
    assertThat(response.getBody().success()).isFalse();
  }

  @Test
  @DisplayName("파일이 없으면 HTTP 404 오류 응답으로 변환한다")
  void mapsNotFoundToNotFound() {
    // given
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    FileErrorCode code = FileErrorCode.NOT_FOUND;
    FileException exception = new FileException(code);

    // when
    ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

    // then
    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code.code());
    assertThat(response.getBody().message()).isEqualTo(code.message());
    assertThat(response.getBody().success()).isFalse();
  }

  @Test
  @DisplayName("파일 읽기 실패는 HTTP 500 오류 응답으로 변환한다")
  void mapsReadFailureToInternalServerError() {
    // given
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    FileErrorCode code = FileErrorCode.READ_FAILED;
    FileException exception = new FileException(code);

    // when
    ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

    // then
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code.code());
    assertThat(response.getBody().message()).isEqualTo(code.message());
    assertThat(response.getBody().success()).isFalse();
  }

  @Test
  @DisplayName("저장소 오류는 HTTP 500 오류 응답으로 변환한다")
  void mapsStorageFailureToInternalServerError() {
    // given
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    FileErrorCode code = FileErrorCode.STORAGE_FAILED;
    FileException exception = new FileException(code);

    // when
    ResponseEntity<ErrorResponse> response = handler.handleBusinessException(exception);

    // then
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(code.code());
    assertThat(response.getBody().message()).isEqualTo(code.message());
    assertThat(response.getBody().success()).isFalse();
  }
}
