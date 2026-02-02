# Router REST API Contract

**Feature**: 001-http-capability-tests
**Date**: 2026-02-02

## Overview

This document defines the REST API contract for interacting with the Wanaku Router for tool management operations. These endpoints are used by the `RouterClient` in the test framework.

## Base URL

```
http://localhost:{routerHttpPort}/api/v1
```

## Authentication

All endpoints require OAuth2 Bearer token authentication:

```
Authorization: Bearer {access_token}
```

Token is obtained from Keycloak using client credentials grant.

---

## Endpoints

### 1. List Tools

**GET** `/tools`

Lists all registered tools.

**Response** `200 OK`:
```json
{
  "tools": [
    {
      "name": "weather-api",
      "description": "Get weather data",
      "type": "http",
      "uri": "http://api.weather.com/current",
      "inputSchema": {
        "type": "object",
        "properties": {
          "city": { "type": "string" }
        },
        "required": ["city"]
      }
    }
  ]
}
```

---

### 2. Register Tool

**POST** `/tools`

Registers a new HTTP tool.

**Request**:
```json
{
  "name": "weather-api",
  "description": "Get weather data",
  "type": "http",
  "uri": "http://localhost:8080/weather",
  "configProperties": {
    "header.Content-Type": "application/json",
    "header.X-Api-Key": "test-key",
    "query.format": "json"
  },
  "inputSchema": {
    "type": "object",
    "properties": {
      "city": { "type": "string" }
    },
    "required": ["city"]
  }
}
```

**Response** `201 Created`:
```json
{
  "name": "weather-api",
  "description": "Get weather data",
  "type": "http",
  "uri": "http://localhost:8080/weather",
  "status": "registered"
}
```

**Response** `409 Conflict` (tool already exists):
```json
{
  "error": "Tool with name 'weather-api' already exists",
  "code": "TOOL_EXISTS"
}
```

---

### 3. Get Tool Info

**GET** `/tools/{name}`

Gets information about a specific tool.

**Response** `200 OK`:
```json
{
  "name": "weather-api",
  "description": "Get weather data",
  "type": "http",
  "uri": "http://localhost:8080/weather",
  "inputSchema": { ... }
}
```

**Response** `404 Not Found`:
```json
{
  "error": "Tool 'weather-api' not found",
  "code": "TOOL_NOT_FOUND"
}
```

---

### 4. Remove Tool

**DELETE** `/tools/{name}`

Removes a registered tool.

**Response** `204 No Content`: Tool successfully removed.

**Response** `404 Not Found`:
```json
{
  "error": "Tool 'weather-api' not found",
  "code": "TOOL_NOT_FOUND"
}
```

---

### 5. Clear All Tools

**DELETE** `/tools`

Removes all registered tools. Used for test cleanup.

**Response** `204 No Content`: All tools removed.

---

## Error Responses

All error responses follow this format:

```json
{
  "error": "Human-readable error message",
  "code": "ERROR_CODE",
  "details": { ... }  // Optional additional context
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| TOOL_EXISTS | 409 | Tool with same name already registered |
| TOOL_NOT_FOUND | 404 | Tool does not exist |
| INVALID_REQUEST | 400 | Request body validation failed |
| UNAUTHORIZED | 401 | Missing or invalid auth token |
| SERVICE_UNAVAILABLE | 503 | HTTP Tool Service not available |

---

## RouterClient Interface

```java
public interface RouterClient {

    /**
     * Register a new HTTP tool.
     * @throws ToolExistsException if tool with same name exists
     */
    ToolInfo registerTool(HttpToolConfig config);

    /**
     * List all registered tools.
     */
    List<ToolInfo> listTools();

    /**
     * Get information about a specific tool.
     * @throws ToolNotFoundException if tool does not exist
     */
    ToolInfo getToolInfo(String name);

    /**
     * Remove a registered tool.
     * @return true if removed, false if not found
     */
    boolean removeTool(String name);

    /**
     * Remove all registered tools.
     * Used for test cleanup.
     */
    void clearAllTools();

    /**
     * Check if a tool exists.
     */
    default boolean toolExists(String name) {
        return listTools().stream()
            .anyMatch(t -> t.getName().equals(name));
    }
}
```
