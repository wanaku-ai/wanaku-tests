package ai.wanaku.test.managers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.quarkus.test.keycloak.server.KeycloakContainer;
import ai.wanaku.test.config.OidcCredentials;

/**
 * Manages the Keycloak container lifecycle.
 * Uses KeycloakTestClient for realm operations and direct HTTP for token acquisition.
 */
public class KeycloakManager {

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakManager.class);

    private static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.3.5";
    private static final String REALM_FILE_PATH = "/wanaku-realm.json";
    private static final int FIXED_HOST_PORT = 8543;

    public static final String REALM_NAME = "wanaku";
    public static final String SERVICE_CLIENT_ID = "wanaku-service";
    public static final String SERVICE_CLIENT_SECRET = "secret";
    public static final String MCP_CLIENT_ID = "mcp-client";
    public static final String TEST_USER = "test-user";
    public static final String TEST_USER_PASSWORD = "test-password";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private FixedPortKeycloakContainer container;
    private KeycloakTestClient keycloakClient;
    private ManagerState state = ManagerState.STOPPED;

    public enum ManagerState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    /**
     * Starts the Keycloak container and initializes the test realm.
     * Creates the wanaku realm with test users and clients.
     *
     * @throws IllegalStateException if Keycloak is already running
     */
    public void start() {
        if (state != ManagerState.STOPPED) {
            throw new IllegalStateException("Keycloak is already running");
        }

        state = ManagerState.STARTING;
        LOG.info("Starting Keycloak container on fixed port {}", FIXED_HOST_PORT);

        container = new FixedPortKeycloakContainer(DEFAULT_IMAGE);
        container.withFixedPort(FIXED_HOST_PORT, 8080);
        container.withLogConsumer(frame -> {});
        container.start();

        keycloakClient = new KeycloakTestClient(container.getServerUrl());
        createRealm();
        awaitOidcDiscovery();
        state = ManagerState.RUNNING;
        LOG.debug("Keycloak started at {}", container.getServerUrl());
    }

    private void awaitOidcDiscovery() {
        String url = container.getServerUrl() + "/realms/" + REALM_NAME + "/.well-known/openid-configuration";
        LOG.debug("Waiting for OIDC discovery: {}", url);
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() -> {
                    HttpRequest req =
                            HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                    return httpClient
                                    .send(req, HttpResponse.BodyHandlers.ofString())
                                    .statusCode()
                            == 200;
                });
        LOG.debug("OIDC discovery endpoint ready");
    }

    private void createRealm() {
        String path = getClass().getResource(REALM_FILE_PATH).getPath();
        RealmRepresentation realm = keycloakClient.readRealmFile(path);

        realm.getClients().stream()
                .filter(c -> c.getClientId().equals(SERVICE_CLIENT_ID))
                .findFirst()
                .ifPresent(c -> c.setSecret(SERVICE_CLIENT_SECRET));

        realm.getClients().stream()
                .filter(c -> c.getClientId().equals(MCP_CLIENT_ID))
                .findFirst()
                .ifPresent(c -> c.setDirectAccessGrantsEnabled(true));

        UserRepresentation testUser = createTestUserRepresentation();
        if (realm.getUsers() == null) {
            realm.setUsers(new java.util.ArrayList<>());
        }
        realm.getUsers().add(testUser);

        keycloakClient.createRealm(realm);
        LOG.debug("Created realm: {} with test user: {}", REALM_NAME, TEST_USER);
    }

    private UserRepresentation createTestUserRepresentation() {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(TEST_USER);
        user.setEmail(TEST_USER + "@test.local");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setFirstName("Test");
        user.setLastName("User");

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(TEST_USER_PASSWORD);
        credential.setTemporary(false);
        user.setCredentials(Collections.singletonList(credential));

        return user;
    }

    /**
     * Stops the Keycloak container.
     */
    public void stop() {
        if (container == null) {
            state = ManagerState.STOPPED;
            return;
        }

        state = ManagerState.STOPPING;
        LOG.debug("Stopping Keycloak container");

        try {
            container.stop();
        } finally {
            container = null;
            keycloakClient = null;
            state = ManagerState.STOPPED;
            LOG.debug("Keycloak stopped");
        }
    }

    /**
     * Checks if the Keycloak container is running.
     *
     * @return true if the container is running
     */
    public boolean isRunning() {
        return container != null && container.isRunning() && state == ManagerState.RUNNING;
    }

    private String getAuthUrl() {
        return container != null ? container.getServerUrl() : null;
    }

    private String getRealmUrl() {
        return String.format("%s/realms/%s", getAuthUrl(), REALM_NAME);
    }

    private String getIssuerUrl() {
        return getRealmUrl();
    }

    private String getTokenEndpoint() {
        return String.format("%s/protocol/openid-connect/token", getRealmUrl());
    }

    /**
     * Gets an MCP token using password grant with the default test user.
     * The token includes the wanaku-mcp-client scope required for MCP operations.
     *
     * @return the access token
     */
    public String getMcpToken() {
        return getMcpTokenForUser(TEST_USER, TEST_USER_PASSWORD);
    }

    /**
     * Gets an MCP token for a specific user using password grant.
     * The token includes the wanaku-mcp-client scope required for MCP operations.
     *
     * @param username the username
     * @param password the password
     * @return the access token
     */
    public String getMcpTokenForUser(String username, String password) {
        try {
            String formData = String.format(
                    "grant_type=%s&client_id=%s&username=%s&password=%s&scope=%s",
                    enc("password"), enc(MCP_CLIENT_ID), enc(username), enc(password), enc("openid wanaku-mcp-client"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getTokenEndpoint()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Failed to get MCP token: " + response.statusCode() + " - " + response.body());
            }

            LOG.debug("Obtained MCP token for user: {}", username);
            return extractToken(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to get MCP token", e);
        }
    }

    /**
     * Gets OIDC credentials for service-to-service authentication.
     * Used by capabilities to register with the Router.
     *
     * @return the OIDC credentials
     * @throws IllegalStateException if Keycloak is not running
     */
    public OidcCredentials getServiceCredentials() {
        if (!isRunning()) {
            throw new IllegalStateException("Keycloak is not running");
        }
        return new OidcCredentials(getIssuerUrl(), SERVICE_CLIENT_ID, SERVICE_CLIENT_SECRET);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String extractToken(String json) {
        Pattern pattern = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("Could not extract access_token from: " + json);
    }

    private static class FixedPortKeycloakContainer extends KeycloakContainer {

        public FixedPortKeycloakContainer(String imageName) {
            super(DockerImageName.parse(imageName));
            this.withUseHttps(false);
            this.waitingFor(Wait.forLogMessage(".*Keycloak.*started.*Listening on:.*", 1));
        }

        public FixedPortKeycloakContainer withFixedPort(int hostPort, int containerPort) {
            addFixedExposedPort(hostPort, containerPort);
            return this;
        }
    }
}
