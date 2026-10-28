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
   * 수동 작업은 실행 후 종료하고 명시적으로 스케줄러를 켠 경우에는 Context를 유지합니다.
   *
   * @param args 애플리케이션 실행 인자
   */
  public static void main(String[] args) {
    var context = SpringApplication.run(DataWorkerApplication.class, args);
    if (!context
        .getEnvironment()
        .getProperty("todayit.place.schedule.enabled", Boolean.class, false)) {
      context.close();
    }
  }
}
