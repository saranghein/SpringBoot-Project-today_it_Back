package com.todayit.common.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.todayit.common.file.entity.File;
import com.todayit.common.file.entity.FileRelatedType;
import com.todayit.common.file.exception.FileErrorCode;
import com.todayit.common.file.exception.FileException;
import com.todayit.common.file.service.model.FileResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest
@DisplayName("파일 등록 동시성")
class FileConcurrencyTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("todayit")
          .withUsername("todayit")
          .withPassword("test-password")
          .withUrlParam("currentSchema", "todayit");

  @Autowired private FileMetadataService metadata;

  @Test
  @DisplayName("같은 문의에 최초 업로드 6개가 동시에 요청되어도 5개까지만 저장한다")
  void concurrentFirstUploadsCannotExceedFiveInquiryAttachments() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
      // given
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Throwable>> attempts = new ArrayList<>();
      for (int index = 0; index < 6; index++) {
        int number = index;
        attempts.add(
            executor.submit(
                () -> {
                  start.await();
                  return catchThrowable(
                      () ->
                          metadata.register(
                              FileRelatedType.INQUIRY_ATTACHMENT,
                              "concurrent-inquiry",
                              List.of(
                                  new File(
                                      FileRelatedType.INQUIRY_ATTACHMENT,
                                      "concurrent-inquiry",
                                      "photo.png",
                                      "concurrent-" + number,
                                      "image/png",
                                      1))));
                }));
      }
      // when
      start.countDown();
      List<Throwable> results = new ArrayList<>();
      for (Future<Throwable> attempt : attempts) {
        results.add(attempt.get());
      }
      List<FileResult> attached =
          metadata.findAll(FileRelatedType.INQUIRY_ATTACHMENT, "concurrent-inquiry");

      // then
      assertThat(results).filteredOn(result -> result == null).hasSize(5);
      assertThat(results)
          .filteredOn(result -> result != null)
          .singleElement()
          .isInstanceOfSatisfying(
              FileException.class,
              exception ->
                  assertThat(exception.getErrorCode())
                      .isEqualTo(FileErrorCode.INQUIRY_LIMIT_EXCEEDED));
      assertThat(attached).hasSize(5);
    }
  }
}
