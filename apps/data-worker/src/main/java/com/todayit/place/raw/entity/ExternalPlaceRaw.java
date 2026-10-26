package com.todayit.place.raw.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 서비스 장소와 별개로 보존하는 공급원 원본입니다. 변경은 Repository의 원자적 upsert로 수행합니다. */
@Entity
@Table(name = "external_place_raw")
public class ExternalPlaceRaw {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "external_place_raw_id")
  private Long id;

  @Column(nullable = false)
  private String provider;

  @Column(name = "source_id", nullable = false)
  private String sourceId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String payload;

  @Column(nullable = false)
  private String status;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "collected_at")
  private Instant collectedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 조회용 생성자입니다. */
  protected ExternalPlaceRaw() {}

  public Long getId() {
    return id;
  }

  public String getProvider() {
    return provider;
  }

  public String getSourceId() {
    return sourceId;
  }

  public String getPayload() {
    return payload;
  }

  public String getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public Instant getCollectedAt() {
    return collectedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
