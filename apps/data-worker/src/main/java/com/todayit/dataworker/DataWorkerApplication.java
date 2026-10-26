package com.todayit.dataworker;

import com.todayit.place.raw.entity.ExternalPlaceRaw;
import com.todayit.place.raw.repository.ExternalPlaceRawRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Today-it 외부 데이터 처리 애플리케이션의 실행 진입점입니다. */
@SpringBootApplication(scanBasePackages = "com.todayit")
@EntityScan(basePackageClasses = ExternalPlaceRaw.class)
@EnableJpaRepositories(basePackageClasses = ExternalPlaceRawRepository.class)
public class DataWorkerApplication {

  /**
   * 일회성 외부 데이터 처리를 실행하고 연결 자원을 종료합니다.
   *
   * @param args 애플리케이션 실행 인자
   */
  public static void main(String[] args) {
    SpringApplication.run(DataWorkerApplication.class, args).close();
  }
}
