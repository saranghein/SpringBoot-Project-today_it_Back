package com.todayit.common.file.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.common.file.entity.File;
import com.todayit.common.file.entity.File.FileSnapshot;
import com.todayit.common.file.entity.FileRelatedType;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest(properties = "spring.jpa.properties.hibernate.default_schema=\"todayit\"")
@Transactional
@DisplayName("파일 메타데이터 저장소")
class FileRepositoryTest {
  @Autowired private FileRepository files;
  @Autowired private EntityManager entities;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("마이그레이션 스키마에서 파일 사용처와 MIME 유형 및 생성 시각을 저장하고 복원한다")
  void persistsRelatedTypeMimeTypeAndTimestampThroughMigrationSchema() {
    // given
    File file =
        new File(
            FileRelatedType.INQUIRY_ATTACHMENT,
            "23",
            "photo.PNG",
            "inquiry_attachment/test",
            "image/png",
            123);

    // when
    File saved = files.saveAndFlush(file);
    long id = saved.getSnapshot().fileId();
    entities.clear();
    FileSnapshot restored = files.findById(id).orElseThrow().getSnapshot();
    String mimeType =
        jdbc.queryForObject(
            "SELECT mime_type FROM \"todayit\".file WHERE file_id = ?", String.class, id);
    List<File> attached =
        files.findByRelatedTypeAndRelatedIdOrderByFileIdAsc(
            FileRelatedType.INQUIRY_ATTACHMENT, "23");

    // then
    assertThat(restored.relatedType()).isEqualTo(FileRelatedType.INQUIRY_ATTACHMENT);
    assertThat(restored.mimeType()).isEqualTo("image/png");
    assertThat(restored.createdAt()).isNotNull();
    assertThat(mimeType).isEqualTo("image/png");
    assertThat(attached).extracting(fileEntity -> fileEntity.getSnapshot().fileId()).contains(id);
  }
}
