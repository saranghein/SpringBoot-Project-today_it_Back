package com.todayit.course.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 코스와 장소를 분류하는 행정 지역을 나타냅니다. */
@Entity
@Table(name = "region")
public class Region {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "region_id", nullable = false)
  private int regionId;

  @Column(name = "sido_code", nullable = false, length = 5)
  private String sidoCode;

  @Column(name = "sigungu_code", nullable = false, length = 5)
  private String sigunguCode;

  @Column(name = "sido_name", nullable = false, length = 10)
  private String sidoName;

  @Column(name = "sigungu_name", nullable = false, length = 10)
  private String sigunguName;

  protected Region() {}
}
