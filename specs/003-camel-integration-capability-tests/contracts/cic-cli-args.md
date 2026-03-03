# CIC CLI Arguments Contract

Command-line arguments for `camel-integration-capability-main-*-jar-with-dependencies.jar`.

## Invocation

```bash
java -jar camel-integration-capability-main-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
  --registration-url http://localhost:{routerHttpPort} \
  --registration-announce-address localhost \
  --grpc-port {dynamicGrpcPort} \
  --name {serviceName} \
  --routes-ref {file:///path/to/routes.yaml | datastore://routes.yaml} \
  --rules-ref {file:///path/to/rules.yaml | datastore://rules.yaml} \
  [--dependencies {file:///path/to/deps.txt | datastore://deps.txt}] \
  --token-endpoint {keycloakUrl}/realms/wanaku \
  --client-id {clientId} \
  --client-secret {clientSecret} \
  [--data-dir /tmp/cic-data] \
  [--retries 12] \
  [--wait-seconds 5] \
  [--no-wait]
```

## Required Arguments

| Argument | Type | Example |
|----------|------|---------|
| `--registration-url` | URL | `http://localhost:8080` |
| `--registration-announce-address` | hostname | `localhost` |
| `--routes-ref` | URI | `file:///tmp/routes.yaml` |
| `--client-id` | string | `wanaku-service` |
| `--client-secret` | string | `kw5xhVcaHIzaw6w8F44qBCmNdB2ERdJj` |

## Optional Arguments

| Argument | Type | Default | Example |
|----------|------|---------|---------|
| `--rules-ref` | URI | (none) | `file:///tmp/rules.yaml` |
| `--dependencies` | CSV URIs | (none) | `file:///tmp/deps.txt` |
| `--grpc-port` | int | `9190` | `9190` |
| `--name` | string | `camel` | `test-db-tool` |
| `--token-endpoint` | URL | auto | `http://localhost:8543/realms/wanaku` |
| `--data-dir` | path | `/tmp` | `/tmp/cic-data` |
| `--repositories` | CSV URLs | (none) | `https://repo1.maven.org/maven2` |
| `--retries` | int | `12` | `3` |
| `--wait-seconds` | int | `5` | `2` |
| `--initial-delay` | long | `5` | `2` |
| `--period` | long | `5` | `2` |
| `--no-wait` | flag | false | (presence = true) |

## URI Schemes

| Scheme | Format | Source |
|--------|--------|--------|
| `file://` | `file:///absolute/path` | Local filesystem |
| `datastore://` | `datastore://filename` | Wanaku Data Store |

## Health Check

CIC does not expose an HTTP health endpoint. Health is determined by:
1. gRPC port is listening (via `HealthCheckUtils.waitForPort()`)
2. Capability is registered with Router (via `RouterClient.isCapabilityRegistered(name)`)