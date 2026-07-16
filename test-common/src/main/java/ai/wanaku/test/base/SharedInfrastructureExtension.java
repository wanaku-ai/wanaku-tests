package ai.wanaku.test.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that starts shared infrastructure (Keycloak + Router) once per JVM.
 * Uses the global extension store so the infrastructure is created exactly once and
 * cleaned up via {@link SharedInfrastructure#close()} when the store is closed.
 */
public class SharedInfrastructureExtension implements BeforeAllCallback {

    private static final Logger LOG = LoggerFactory.getLogger(SharedInfrastructureExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        SharedInfrastructure infra = context.getRoot()
                .getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent(
                        SharedInfrastructure.class,
                        key -> {
                            SharedInfrastructure si = new SharedInfrastructure();
                            try {
                                si.start();
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to start shared infrastructure", e);
                            }
                            return si;
                        },
                        SharedInfrastructure.class);

        BaseIntegrationTest.config = infra.getConfig();
        BaseIntegrationTest.keycloakManager = infra.getKeycloakManager();
        BaseIntegrationTest.routerManager = infra.getRouterManager();
        BaseIntegrationTest.tempDataDir = infra.getTempDataDir();
    }
}
