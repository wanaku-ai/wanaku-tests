# Data Store REST API Contract

Used by DataStoreClient to upload/download configuration files for CIC `datastore://` tests.

## Endpoints

### POST /api/v1/data-store/add

Upload a file to the Data Store.

**Request:**
```json
{
  "name": "finance-routes.camel.yaml",
  "data": "LSByb3V0ZTo...",
  "labels": {
    "test": "cic-integration"
  }
}
```

- `name`: file identifier (used in `datastore://` references)
- `data`: base64-encoded file content
- `labels`: optional key-value pairs

**Response (200):**
```json
{
  "error": null,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "finance-routes.camel.yaml",
    "data": "LSByb3V0ZTo...",
    "labels": { "test": "cic-integration" }
  }
}
```

### GET /api/v1/data-store/list

List all entries in the Data Store.

**Query Parameters:**
- `labelFilter` (optional): Label expression (e.g., `test=cic-integration`)

**Response (200):**
```json
{
  "error": null,
  "data": [
    { "id": "...", "name": "...", "data": "...", "labels": {...} }
  ]
}
```

### GET /api/v1/data-store/get

Get a specific entry by ID or name.

**Query Parameters:**
- `id` (optional): UUID of the entry
- `name` (optional): File name

**Response (200):**
```json
{
  "error": null,
  "data": { "id": "...", "name": "...", "data": "...", "labels": {...} }
}
```

### DELETE /api/v1/data-store/remove

Remove an entry by ID or name.

**Query Parameters:**
- `id` (optional): UUID to remove
- `name` (optional): Name to remove (removes all matching)

**Response (200):** Empty body on success.

## Test Usage Pattern

```java
// Upload config to Data Store
dataStoreClient.upload("routes.yaml", routesYamlContent);
dataStoreClient.upload("rules.yaml", rulesYamlContent);

// Start CIC with datastore:// references
manager.prepare(config);
config.setRoutesRef("datastore://routes.yaml");
config.setRulesRef("datastore://rules.yaml");
manager.start("test");

// After test: cleanup
dataStoreClient.removeByName("routes.yaml");
dataStoreClient.removeByName("rules.yaml");
```
