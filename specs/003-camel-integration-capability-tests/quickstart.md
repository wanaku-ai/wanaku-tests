# Quickstart: CIC Tests

## Prerequisites

```bash
# Download all artifacts (including CIC JAR)
./artifacts/download.sh

# Verify CIC JAR exists
ls artifacts/camel-integration-capability/
# → camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Run Tests

```bash
# All CIC tests
mvn clean install -pl camel-integration-capability-tests

# Specific test class
mvn clean install -pl camel-integration-capability-tests -Dtest=CamelToolITCase

# Single test
mvn clean install -pl camel-integration-capability-tests \
  -Dtest=CamelToolITCase#shouldInvokeSimpleToolViaMcp
```

## Write a New Test

### 1. Simple Tool Test

```java
@QuarkusTest
class MyToolITCase extends CamelCapabilityTestBase {

    @Test
    void shouldInvokeMyTool() throws Exception {
        // Load fixture files (routes.camel.yaml + rules.yaml)
        Path fixtures = TestFixtures.load("simple-tool");

        // Start CIC with fixture configs
        startCapability("my-tool-service", fixtures);

        // Invoke via MCP
        mcpClient.when()
            .toolsCall("my-tool")
            .withAssert(response -> {
                assertThat(response.content()).isNotEmpty();
                assertThat(response.content().get(0).asText().text())
                    .contains("Hello from CIC!");
            })
            .send();
    }
}
```

**Fixture files** (`src/test/resources/fixtures/simple-tool/`):

`routes.camel.yaml`:
```yaml
- route:
    id: my-route
    from:
      uri: direct:my-route
      steps:
        - setBody:
            constant: "Hello from CIC!"
```

`rules.yaml`:
```yaml
mcp:
  tools:
    - my-tool:
        route:
          id: "my-route"
        description: "Returns a greeting"
```

### 2. Database Tool Test

```java
@Test
void shouldQueryDatabase() throws Exception {
    // Load fixtures with dynamic PostgreSQL URL substitution
    Path fixtures = TestFixtures.load("postgres-tool", Map.of(
        "JDBC_URL", postgresManager.getJdbcUrl(),
        "DB_USER", postgresManager.getUsername(),
        "DB_PASSWORD", postgresManager.getPassword()
    ));

    startCapability("db-tool", fixtures);

    mcpClient.when()
        .toolsCall("query-users")
        .withAssert(response -> {
            assertThat(response.content()).isNotEmpty();
        })
        .send();
}
```

### 3. File Resource Test

```java
@Test
void shouldReadFileResource() throws Exception {
    Path testFile = createTestFile("policy.txt", "Company policy content");

    // Load fixtures with dynamic file path substitution
    Path fixtures = TestFixtures.load("file-resource", Map.of(
        "FILE_DIR", testFile.getParent().toString(),
        "FILE_NAME", testFile.getFileName().toString()
    ));

    startCapability("policy-service", fixtures);

    mcpClient.when()
        .resourcesRead("company-policy")
        .withAssert(response -> {
            assertThat(response.contents().get(0).asText().text())
                .contains("Company policy content");
        })
        .send();
}
```

## Project Structure

```
camel-integration-capability-tests/
├── pom.xml
└── src/test/java/ai/wanaku/test/camel/
    ├── CamelCapabilityTestBase.java     # Base class
    ├── CamelToolITCase.java             # Tool registration & invocation
    ├── CamelResourceITCase.java         # Resource exposure & reading
    ├── CamelDatabaseToolITCase.java     # PostgreSQL integration
    ├── CamelDataStoreITCase.java        # datastore:// loading
    └── CamelMultiInstanceITCase.java    # Multiple concurrent instances
```
