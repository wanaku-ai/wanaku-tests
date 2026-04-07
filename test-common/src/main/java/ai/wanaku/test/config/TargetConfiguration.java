package ai.wanaku.test.config;

/**
 * Bundles router/target connection details for manager preparation.
 *
 * @param routerHost the Router host
 * @param routerHttpPort the Router HTTP port used for registration
 * @param routerGrpcPort the Router gRPC port used for capability communication
 * @param oidcCredentials optional OIDC credentials, or null if authentication is disabled
 */
public record TargetConfiguration(
        String routerHost, int routerHttpPort, int routerGrpcPort, OidcCredentials oidcCredentials) {

    /**
     * Builds the Router registration URI from the configured host and HTTP port.
     *
     * @return the registration URI
     */
    public String registrationUri() {
        return String.format("http://%s:%d", routerHost, routerHttpPort);
    }
}
