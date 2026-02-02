# MCP Client Contract

**Feature**: 001-http-capability-tests
**Date**: 2026-02-02

## Overview

This document defines the contract for interacting with the Wanaku Router via the MCP (Model Context Protocol). The test framework uses the MCPAssured client from Quarkus MCP Server project.

## Transport

MCP communication uses Server-Sent Events (SSE) over HTTP:

```
http://localhost:{routerHttpPort}/mcp/sse
```

## Authentication

OAuth2 Bearer token authentication (same as REST API):

```
Authorization: Bearer {access_token}
```

---

## MCP Operations

### 1. List Tools

Lists all available tools via MCP protocol.

**Request** (JSON-RPC over SSE):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "weather-api",
        "description": "Get weather data from external API",
        "inputSchema": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "City name"
            }
          },
          "required": ["city"]
        }
      }
    ]
  }
}
```

---

### 2. Invoke Tool

Invokes a registered tool with parameters.

**Request**:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "weather-api",
    "arguments": {
      "city": "London"
    }
  }
}
```

**Successful Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"temperature\": 15, \"conditions\": \"cloudy\"}"
      }
    ],
    "isError": false
  }
}
```

**Error Response** (tool execution failed):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Connection refused: http://localhost:9999/weather"
      }
    ],
    "isError": true
  }
}
```

**Error Response** (tool not found):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": {
    "code": -32602,
    "message": "Tool 'unknown-tool' not found"
  }
}
```

---

## MCPAssured Usage Pattern

The test framework uses MCPAssured from Quarkus MCP Server:

```java
import io.quarkiverse.mcp.server.test.McpAssured;

// Setup
McpClient client = McpAssured.client()
    .baseUri(routerManager.getMcpUrl())
    .accessToken(keycloakManager.getAccessToken())
    .build();

// List tools
List<Tool> tools = client.listTools();
assertThat(tools).hasSize(1);
assertThat(tools.get(0).name()).isEqualTo("weather-api");

// Invoke tool
ToolCallResult result = client.callTool("weather-api", Map.of("city", "London"));
assertThat(result.isError()).isFalse();
assertThat(result.content()).contains("temperature");

// Handle errors
ToolCallResult errorResult = client.callTool("unreachable-api", Map.of());
assertThat(errorResult.isError()).isTrue();
assertThat(errorResult.content()).contains("Connection refused");
```

---

## Test Assertions

### Tool Listing Assertions

```java
// Verify tool exists
assertThat(client.listTools())
    .extracting(Tool::name)
    .contains("weather-api");

// Verify tool metadata
Tool tool = client.getTool("weather-api");
assertThat(tool.description()).isEqualTo("Get weather data");
assertThat(tool.inputSchema().required()).contains("city");

// Verify tool count
assertThat(client.listTools()).hasSize(expectedCount);

// Verify tool does not exist (after removal)
assertThat(client.listTools())
    .extracting(Tool::name)
    .doesNotContain("removed-tool");
```

### Tool Invocation Assertions

```java
// Successful invocation
ToolCallResult result = client.callTool("weather-api", args);
assertThat(result.isError()).isFalse();

// Response content validation
String responseText = result.content().get(0).text();
JsonNode json = objectMapper.readTree(responseText);
assertThat(json.get("temperature").asInt()).isEqualTo(15);

// Error invocation
ToolCallResult error = client.callTool("failing-api", args);
assertThat(error.isError()).isTrue();
assertThat(error.content().get(0).text()).contains("expected error");
```

---

## MockHttpServer Integration

When testing HTTP tool invocations, the mock server receives the actual HTTP requests from the HTTP Tool Service:

```java
// Setup mock response
mockServer.enqueue(new MockResponse()
    .setBody("{\"temperature\": 15}")
    .setHeader("Content-Type", "application/json"));

// Register tool pointing to mock
routerClient.registerTool(HttpToolConfig.builder()
    .name("weather-api")
    .uri(mockServer.url("/weather").toString())
    .build());

// Invoke via MCP
ToolCallResult result = mcpClient.callTool("weather-api", Map.of("city", "London"));

// Verify mock received request
RecordedRequest request = mockServer.takeRequest();
assertThat(request.getPath()).isEqualTo("/weather");
assertThat(request.getMethod()).isEqualTo("GET");
```
