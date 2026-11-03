package ai.wanaku.test.base;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SharedInfrastructureExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
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
        BaseIntegrationTest.serverManager = infra.getServerManager();
        BaseIntegrationTest.tempDataDir = infra.getTempDataDir();
    }
}
