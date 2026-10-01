package kr.ac.skuniv.coopradar;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 Postgres. 배포(Render)와 같은 18을 컨테이너로 띄운다(ADR-0010).
 * V1이 배열·num_nonnulls·부분 유니크 인덱스를 써서 H2로는 대신할 수 없다.
 * {@code @SpringBootTest}마다 {@code @Import(TestcontainersConfiguration.class)}를 붙인다.
 * 컨텍스트가 같으면 컨테이너 하나를 같이 쓴다. Docker가 떠 있어야 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }
}
