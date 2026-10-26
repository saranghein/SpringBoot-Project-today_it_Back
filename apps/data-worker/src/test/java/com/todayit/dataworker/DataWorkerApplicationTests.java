package com.todayit.dataworker;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.place.raw.repository.ExternalPlaceRawRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 기본 worker 컨텍스트가 외부 호출 없이 시작되는지 검증합니다. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:worker-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
      "todayit.place.collection.enabled=false",
      "TOUR_API_ENABLED=false",
      "REDTABLE_ENABLED=false",
      "CULTURE_ENABLED=false"
    })
class DataWorkerApplicationTests {

  @Autowired private ExternalPlaceRawRepository repository;

  /** 공급원을 활성화하지 않은 기본 설정으로 worker 컨텍스트가 시작되는지 검증합니다. */
  @Test
  void contextLoads() {
    assertThat(repository.count()).isZero();
  }
}
