package com.todayit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"test", "local"})
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:local_seed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
class FlywayLocalSeedTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void seedsOneDummyMemberForEachRole() {
    List<String> roleNames =
        jdbcTemplate.queryForList(
            """
            SELECT roles.name
            FROM "todayit".member_roles
            JOIN "todayit".roles ON roles.roles_id = member_roles.roles_id
            JOIN "todayit".member ON member.member_id = member_roles.member_id
            WHERE member.email IN (
                'user@todayit.local',
                'admin@todayit.local',
                'dev@todayit.local'
            )
            ORDER BY roles.name
            """,
            String.class);

    assertThat(roleNames).containsExactly("ADMIN", "DEV", "USER");
  }
}
