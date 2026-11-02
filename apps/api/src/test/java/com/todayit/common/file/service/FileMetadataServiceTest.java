package com.todayit.common.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.common.file.config.FileUploadProperties;
import com.todayit.common.file.entity.File;
import com.todayit.common.file.entity.FileRelatedType;
import com.todayit.common.file.exception.FileErrorCode;
import com.todayit.common.file.exception.FileException;
import com.todayit.common.file.repository.FileRepository;
import com.todayit.common.file.service.model.FileRegistrationResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

@DisplayName("파일 메타데이터 서비스")
class FileMetadataServiceTest {
  private final FileRepository files = mock(FileRepository.class);
  private final FileMetadataService service =
      new FileMetadataService(files, new FileUploadProperties(5, DataSize.ofMegabytes(30)));

  @Test
  @DisplayName("기존 문의 첨부를 합산해 30 MiB를 초과하면 추가 저장하지 않는다")
  void countsAlreadyStoredInquiryFiles() {
    // given
    when(files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.INQUIRY_ATTACHMENT, "23"))
        .thenReturn(
            List.of(inquiry(1, 10_485_760), inquiry(2, 10_485_760), inquiry(3, 10_485_760)));
    List<File> additions = List.of(inquiry(4, 1));

    // when
    Throwable thrown =
        catchThrowable(() -> service.register(FileRelatedType.INQUIRY_ATTACHMENT, "23", additions));

    // then
    assertFileError(thrown, FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
    verify(files, never()).saveAllAndFlush(anyList());
  }

  @Test
  @DisplayName("프로필을 교체하고 외부 저장소에서 정리할 이전 파일 정보를 반환한다")
  void replacesProfileAndReturnsPreviousStorageFile() {
    // given
    File old = stored(1, FileRelatedType.MEMBER_PROFILE, "member", "old-key", 12);
    File added = stored(2, FileRelatedType.MEMBER_PROFILE, "member", "new-key", 12);
    when(files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.MEMBER_PROFILE, "member"))
        .thenReturn(List.of(old));
    when(files.saveAllAndFlush(List.of(added))).thenReturn(List.of(added));

    // when
    FileRegistrationResult result =
        service.register(FileRelatedType.MEMBER_PROFILE, "member", List.of(added));

    // then
    assertThat(result.files())
        .singleElement()
        .satisfies(file -> assertThat(file.storedName()).isEqualTo("new-key"));
    verify(files).deleteAll(List.of(old));
    assertThat(result.replaced())
        .singleElement()
        .satisfies(file -> assertThat(file.storedName()).isEqualTo("old-key"));
  }

  @Test
  @DisplayName("다른 대상에 속한 파일은 조회할 수 없다")
  void rejectsAccessThroughAnotherTarget() {
    // given
    when(files.findById(1L)).thenReturn(Optional.of(inquiry(1, 10)));

    // when
    Throwable thrown =
        catchThrowable(() -> service.get(FileRelatedType.INQUIRY_ATTACHMENT, "another", 1));

    // then
    assertFileError(thrown, FileErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("다른 대상에 속한 파일은 삭제할 수 없다")
  void rejectsDeletionThroughAnotherTarget() {
    // given
    when(files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.INQUIRY_ATTACHMENT, "another"))
        .thenReturn(List.of());

    // when
    Throwable thrown =
        catchThrowable(
            () -> service.delete(FileRelatedType.INQUIRY_ATTACHMENT, "another", List.of(1L)));

    // then
    assertFileError(thrown, FileErrorCode.NOT_FOUND);
    verify(files, never()).deleteAll(anyList());
  }

  @Test
  @DisplayName("기존 파일을 포함한 문의 첨부 개수에 변경된 설정을 적용한다")
  void appliesConfiguredCountToExistingAttachments() {
    // given
    FileMetadataService configured =
        new FileMetadataService(files, new FileUploadProperties(1, DataSize.ofMegabytes(30)));
    when(files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.INQUIRY_ATTACHMENT, "23"))
        .thenReturn(List.of(inquiry(1, 1)));

    // when
    Throwable thrown =
        catchThrowable(
            () ->
                configured.register(
                    FileRelatedType.INQUIRY_ATTACHMENT, "23", List.of(inquiry(2, 1))));

    // then
    assertFileError(thrown, FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
    verify(files, never()).saveAllAndFlush(anyList());
  }

  @Test
  @DisplayName("기존 파일을 포함한 문의 첨부 용량에 변경된 설정을 적용한다")
  void appliesConfiguredTotalSizeToExistingAttachments() {
    // given
    FileMetadataService configured =
        new FileMetadataService(files, new FileUploadProperties(5, DataSize.ofBytes(10)));
    when(files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.INQUIRY_ATTACHMENT, "23"))
        .thenReturn(List.of(inquiry(1, 10)));

    // when
    Throwable thrown =
        catchThrowable(
            () ->
                configured.register(
                    FileRelatedType.INQUIRY_ATTACHMENT, "23", List.of(inquiry(2, 1))));

    // then
    assertFileError(thrown, FileErrorCode.INQUIRY_LIMIT_EXCEEDED);
    verify(files, never()).saveAllAndFlush(anyList());
  }

  private void assertFileError(Throwable thrown, FileErrorCode expected) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FileException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
  }

  private File inquiry(long id, long bytes) {
    return stored(id, FileRelatedType.INQUIRY_ATTACHMENT, "23", "key-" + id, bytes);
  }

  private File stored(long id, FileRelatedType type, String target, String key, long bytes) {
    File file = new File(type, target, "photo.png", key, "image/png", bytes);
    ReflectionTestUtils.setField(file, "fileId", id);
    return file;
  }
}
