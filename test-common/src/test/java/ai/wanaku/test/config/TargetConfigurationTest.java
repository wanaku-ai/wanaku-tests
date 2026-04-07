package ai.wanaku.test.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TargetConfigurationTest {

    @Test
    void buildsRegistrationUriFromRouterHostAndHttpPort() {
        TargetConfiguration target = new TargetConfiguration(
                "localhost", 8080, 9090, new OidcCredentials("http://auth/realms/wanaku", "client", "secret"));

        assertThat(target.registrationUri()).isEqualTo("http://localhost:8080");
        assertThat(target.routerHost()).isEqualTo("localhost");
        assertThat(target.routerHttpPort()).isEqualTo(8080);
        assertThat(target.routerGrpcPort()).isEqualTo(9090);
    }

    @Test
    void allowsOidcCredentialsToBeAbsent() {
        TargetConfiguration target = new TargetConfiguration("router", 8181, 9191, null);

        assertThat(target.oidcCredentials()).isNull();
        assertThat(target.registrationUri()).isEqualTo("http://router:8181");
    }
}
