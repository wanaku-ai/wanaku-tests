# Procedure: Service Template Checks

Reusable checks for a built-in service template. The service template test plans use these checks for the parts that are the same for
every template: the source structure, the listing, the instantiation and the tool registration in Wanaku Server.
Each plan adds only the checks that are specific to its template (route content, backend setup, invocation).

A service template becomes an MCP tool in four steps:

1. Barn stores the template. Barn deploys the built-in templates at startup.
2. `wanaku service template instantiate` creates a service catalog in Barn from the template.
3. A Camel Integration Capability (CIC) runtime serves the catalog (see [start-cic.md](start-cic.md)).
4. Wanaku Server discovers the tool through the CIC forward.

Run sections A to C before the CIC starts. Run section D after [start-cic.md](start-cic.md). Run section E in the
cleanup phase of the plan.

## Prerequisites

- Run the commands from the root of this repository (wanaku-barn).
- Wanaku Barn started (see [start-barn.md](start-barn.md)). `WANAKU_BARN_URL` and `CLI_JAR` must be set.
- Section D only: Wanaku Server started (see [start-server.md](start-server.md)), `WANAKU_SERVER_URL` and
  `WANAKU_MCP_BASE_URL` set, and the catalog served by a CIC runtime (see [start-cic.md](start-cic.md)).
- `jq`, `curl` and `unzip`.

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `TEMPLATE_NAME` | Template name (`catalog.name` of the template) | _(required)_ |
| `TEMPLATE_SYSTEM` | System (service) name inside the template | _(required)_ |
| `TEMPLATE_TOOL` | MCP tool name. This is the `ai-tool:<name>` of the route. | _(required)_ |
| `TEMPLATE_TOOL_PARAMS` | Space-separated tool parameter names (`parameter.<name>` in the route) | _(required)_ |
| `TEMPLATE_SEARCH` | Search term that must find the template | _(required)_ |
| `TEMPLATE_PROPS_FILE` | Java properties file with the instantiation values | _(required)_ |
| `TEMPLATE_CATALOG` | Name of the catalog that the instantiation creates (`--service-name`) | `${TEMPLATE_NAME}-test` |
| `TEMPLATE_BASE_DIR` | Directory of the built-in template sources | `services/service-templates/src/main/services` |
| `TEMPLATE_WORK_DIR` | Directory for downloaded packages | `$(mktemp -d)` |

`TEMPLATE_CATALOG` is different from `TEMPLATE_NAME` on purpose. An instantiation without `--service-name` creates a
catalog with the template name, and that name can collide with catalogs of other runs on a shared Barn.

## Output variables

| Variable | Description |
|----------|-------------|
| `TEMPLATE_DIR` | Source directory of the template |
| `TEMPLATE_ROUTE_FILE` | Camel route file of `TEMPLATE_SYSTEM` |
| `TEMPLATE_PROPS_PATH` | `service.properties` path inside the package (relative to the package root) |

## Phase S: Setup

### Step S.1: Set template check variables

Run this block as a separate `bash` script, not in the shared session.

```bash
export TEMPLATE_CATALOG="${TEMPLATE_CATALOG:-${TEMPLATE_NAME}-test}"
export TEMPLATE_BASE_DIR="${TEMPLATE_BASE_DIR:-services/service-templates/src/main/services}"
export TEMPLATE_WORK_DIR="${TEMPLATE_WORK_DIR:-$(mktemp -d)}"
export TEMPLATE_DIR="${TEMPLATE_BASE_DIR}/${TEMPLATE_NAME}"

for VAR in TEMPLATE_NAME TEMPLATE_SYSTEM TEMPLATE_TOOL TEMPLATE_TOOL_PARAMS TEMPLATE_SEARCH TEMPLATE_PROPS_FILE \
  WANAKU_BARN_URL CLI_JAR; do
  eval "VALUE=\${${VAR}}"
  if [ -z "${VALUE}" ]; then
    echo "FAIL: ${VAR} must be set"
    exit 1
  fi
done
[ -f "${TEMPLATE_PROPS_FILE}" ] || { echo "FAIL: ${TEMPLATE_PROPS_FILE} not found"; exit 1; }
echo "PASS: template check variables are set (catalog: ${TEMPLATE_CATALOG})"
```

**Expected result:**

- The output includes `PASS: template check variables are set (catalog: <TEMPLATE_CATALOG>)`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

## Phase A: Template source structure

### Test A.1: The template directory has the required files

The route file is the file that `catalog.routes.<system>` declares. The properties file is the file that
`catalog.properties.<system>` declares, or `<system>/service.properties` by convention.

Run this block as a separate `bash` script, not in the shared session.

```bash
INDEX="${TEMPLATE_DIR}/index.properties"
[ -f "${INDEX}" ] && echo "PASS: index.properties exists" || { echo "FAIL: ${INDEX} missing"; exit 1; }

ROUTES_REL=$(grep "^catalog.routes.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
DEPS_REL=$(grep "^catalog.dependencies.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
TEMPLATE_PROPS_PATH=$(grep "^catalog.properties.${TEMPLATE_SYSTEM}=" "${INDEX}" | cut -d= -f2-)
TEMPLATE_PROPS_PATH="${TEMPLATE_PROPS_PATH:-${TEMPLATE_SYSTEM}/service.properties}"
export TEMPLATE_ROUTE_FILE="${TEMPLATE_DIR}/${ROUTES_REL}"
export TEMPLATE_PROPS_PATH

for FILE in "${TEMPLATE_ROUTE_FILE}" "${TEMPLATE_DIR}/${TEMPLATE_PROPS_PATH}"; do
  [ -n "${FILE}" ] && [ -f "${FILE}" ] && echo "PASS: ${FILE} exists" || echo "FAIL: ${FILE} missing"
done

if [ -n "${DEPS_REL}" ]; then
  [ -f "${TEMPLATE_DIR}/${DEPS_REL}" ] && echo "PASS: ${DEPS_REL} exists" || echo "FAIL: ${DEPS_REL} missing"
fi
```

**Expected result:**

- The output includes `PASS: index.properties exists`.
- The output includes `PASS: <FILE> exists` for both `TEMPLATE_ROUTE_FILE` and the declared properties file.
- If `DEPS_REL` is nonempty, the output includes `PASS: <DEPS_REL> exists`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test A.2: index.properties declares the catalog

```bash run
grep -q "^catalog.name=${TEMPLATE_NAME}$" "${INDEX}" && echo "PASS: catalog.name" || echo "FAIL: catalog.name"
grep -q "^catalog.description=." "${INDEX}" && echo "PASS: catalog.description" || echo "FAIL: catalog.description"
grep "^catalog.services=" "${INDEX}" | grep -q "${TEMPLATE_SYSTEM}" \
  && echo "PASS: catalog.services contains ${TEMPLATE_SYSTEM}" || echo "FAIL: catalog.services"
```

**Expected result:**

- The output includes `PASS: catalog.name`.
- The output includes `PASS: catalog.description`.
- The output includes `PASS: catalog.services contains <TEMPLATE_SYSTEM>`.
- The output contains no line starting with `FAIL:`.

### Test A.3: The route is an MCP tool route

The CIC serves a route as an MCP tool when the route starts from `ai-tool:<name>`. Templates do not use rules files
(`*.rules.yaml`). Rules files are optional since 0.3.0.

```bash run
grep -q "uri: ai-tool:${TEMPLATE_TOOL}$" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: route starts from ai-tool:${TEMPLATE_TOOL}" || echo "FAIL: ai-tool:${TEMPLATE_TOOL} not found"
grep -q "tags: wanaku" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: route has the wanaku tag" || echo "FAIL: wanaku tag missing"
grep -q "description:" "${TEMPLATE_ROUTE_FILE}" \
  && echo "PASS: tool description present" || echo "FAIL: tool description missing"

for PARAM in ${TEMPLATE_TOOL_PARAMS}; do
  grep -q "parameter.${PARAM}:" "${TEMPLATE_ROUTE_FILE}" \
    && echo "PASS: tool parameter '${PARAM}' declared" || echo "FAIL: tool parameter '${PARAM}' missing"
done
```

**Expected result:**

- The output includes `PASS: route starts from ai-tool:<TEMPLATE_TOOL>`.
- The output includes `PASS: route has the wanaku tag`.
- The output includes `PASS: tool description present`.
- For every parameter in `TEMPLATE_TOOL_PARAMS`, the output includes `PASS: tool parameter '<PARAM>' declared`.
- The output contains no line starting with `FAIL:`.

### Test A.4: No credentials are hard-coded

```bash run
FOUND=0
for FILE in $(find "${TEMPLATE_DIR}" -type f); do
  if grep -Eq '(sk-[a-zA-Z0-9]{20,}|key-[a-zA-Z0-9]{20,}|pcsk_[a-zA-Z0-9]{20,}|Bearer [a-zA-Z0-9]{20,})' "${FILE}"; then
    echo "FAIL: possible hard-coded credential in ${FILE}"
    FOUND=1
  fi
done
[ "${FOUND}" -eq 0 ] && echo "PASS: no hard-coded credentials"
```

**Expected result:**

- The output includes `PASS: no hard-coded credentials`.
- The output contains no line starting with `FAIL:`.

### Test A.5: The catalog name is unique among the templates

```bash run
DUP=$(grep -l "^catalog.name=${TEMPLATE_NAME}$" "${TEMPLATE_BASE_DIR}"/*/index.properties | wc -l | tr -d ' ')
[ "${DUP}" -eq 1 ] && echo "PASS: catalog.name is unique" || echo "FAIL: catalog.name is used ${DUP} times"
```

**Expected result:**

- The output includes `PASS: catalog.name is unique`.
- The output contains no line starting with `FAIL:`.

### Test A.6: Barn validates the template

`wanaku service validate` sends the package to Barn. Barn does not deploy it. Warnings (for example a missing
`catalog.icon`) do not fail the validation.

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service validate --template --path="${TEMPLATE_DIR}" \
  --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service template '${TEMPLATE_NAME}' is valid" \
  && echo "PASS: Barn reports the template as valid" || echo "FAIL: template validation failed (exit ${EXIT_CODE})"
```

**Expected result:**

- The output includes `PASS: Barn reports the template as valid`.
- The output contains no line starting with `FAIL:`.

## Phase B: Listing

### Test B.1: The CLI lists the template

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_NAME}[[:space:]]" \
  && echo "PASS: template list contains ${TEMPLATE_NAME}" || echo "FAIL: ${TEMPLATE_NAME} not in template list"
```

**Expected result:**

- The output includes `PASS: template list contains <TEMPLATE_NAME>`.
- The output contains no line starting with `FAIL:`.

### Test B.2: The search finds the template

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search "${TEMPLATE_SEARCH}" 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_NAME}[[:space:]]" \
  && echo "PASS: search '${TEMPLATE_SEARCH}' finds ${TEMPLATE_NAME}" || echo "FAIL: search did not find ${TEMPLATE_NAME}"
```

**Expected result:**

- The output includes `PASS: search '<TEMPLATE_SEARCH>' finds <TEMPLATE_NAME>`.
- The output contains no line starting with `FAIL:`.

### Test B.3: A search for an unrelated term does not find the template (negative)

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template list --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --search "zzz-nonexistent-zzz" 2>&1)
echo "${OUTPUT}" | grep -q "${TEMPLATE_NAME}" \
  && echo "FAIL: unrelated search returned ${TEMPLATE_NAME}" \
  || { echo "${OUTPUT}" | grep -q "No service templates found" \
       && echo "PASS: unrelated search returns no templates" || echo "FAIL: unexpected output: ${OUTPUT}"; }
```

**Expected result:**

- The output includes `PASS: unrelated search returns no templates`.
- The output contains no line starting with `FAIL:`.

### Test B.4: The template detail, properties and package endpoints

The CLI has no command for the template detail, the property list and the current package. `curl` is acceptable
here (see [Write a test plan](../../docs/test-plans/write-a-test-plan.md)).

```bash run
DETAIL=$(curl -s "${WANAKU_BARN_URL}/api/v1/service-template/get?name=${TEMPLATE_NAME}")
echo "${DETAIL}" | jq -e --arg n "${TEMPLATE_NAME}" '.error == null and .data.name == $n' > /dev/null \
  && echo "PASS: detail returns ${TEMPLATE_NAME}" || echo "FAIL: detail response: ${DETAIL}"
echo "${DETAIL}" | jq -e --arg s "${TEMPLATE_SYSTEM}" '[.data.services[].name] | index($s) != null' > /dev/null \
  && echo "PASS: detail lists system ${TEMPLATE_SYSTEM}" || echo "FAIL: system ${TEMPLATE_SYSTEM} not in detail"

PROPS=$(curl -s "${WANAKU_BARN_URL}/api/v1/service-template/properties?name=${TEMPLATE_NAME}")
echo "${PROPS}" | jq -e --arg s "${TEMPLATE_SYSTEM}" '.data[$s] | type == "object"' > /dev/null \
  && echo "PASS: properties endpoint returns the properties of ${TEMPLATE_SYSTEM}" \
  || echo "FAIL: properties response: ${PROPS}"

curl -s "${WANAKU_BARN_URL}/api/v1/service-template/download?name=${TEMPLATE_NAME}" \
  | jq -r '.data.data' | base64 -d > "${TEMPLATE_WORK_DIR}/template.zip" 2>/dev/null
unzip -l "${TEMPLATE_WORK_DIR}/template.zip" 2>/dev/null | grep -q "index.properties" \
  && echo "PASS: download returns a ZIP package with index.properties" || echo "FAIL: download is not a valid package"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "${WANAKU_BARN_URL}/api/v1/service-template/get?name=does-not-exist-99999")
[ "${HTTP_CODE}" = "404" ] && echo "PASS: unknown template returns 404" || echo "FAIL: expected 404, got ${HTTP_CODE}"
```

**Expected result:**

- The output includes `PASS: detail returns <TEMPLATE_NAME>`.
- The output includes `PASS: detail lists system <TEMPLATE_SYSTEM>`.
- The output includes `PASS: properties endpoint returns the properties of <TEMPLATE_SYSTEM>`.
- The output includes `PASS: download returns a ZIP package with index.properties`.
- The output includes `PASS: unknown template returns 404`.
- The output contains no line starting with `FAIL:`.

## Phase C: Instantiation

### Test C.1: Instantiate the template with a properties file

The plan writes `TEMPLATE_PROPS_FILE` before this step.

Run this block as a separate `bash` script, not in the shared session.

```bash
[ -s "${TEMPLATE_PROPS_FILE}" ] || { echo "FAIL: ${TEMPLATE_PROPS_FILE} is empty"; exit 1; }
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" \
  --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "${TEMPLATE_CATALOG}" 2>&1)
EXIT_CODE=$?
echo "${OUTPUT}"
[ "${EXIT_CODE}" -eq 0 ] && echo "${OUTPUT}" | grep -q "Service catalog created successfully from template" \
  && echo "PASS: catalog ${TEMPLATE_CATALOG} created" || { echo "FAIL: instantiation failed (exit ${EXIT_CODE})"; exit 1; }
```

**Expected result:**

- The output includes `PASS: catalog <TEMPLATE_CATALOG> created`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test C.2: The catalog is in the catalog list

```bash run
java -jar "${CLI_JAR}" service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1 \
  | grep -q "^${TEMPLATE_CATALOG}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_CATALOG} is in the catalog list" || echo "FAIL: ${TEMPLATE_CATALOG} not in the catalog list"
```

**Expected result:**

- The output includes `PASS: <TEMPLATE_CATALOG> is in the catalog list`.
- The output contains no line starting with `FAIL:`.

### Test C.3: The catalog contains the supplied values

The first instantiation creates version 1 of the catalog. Barn writes the properties with `java.util.Properties`,
which escapes some characters (for example `https\://`). The check compares the keys and makes sure that no supplied
key keeps its `{{placeholder}}`.

Run this block as a separate `bash` script, not in the shared session.

```bash
java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}" --version 1 --output "${TEMPLATE_WORK_DIR}/catalog-v1.zip" > /dev/null 2>&1 \
  && echo "PASS: version 1 downloaded" || { echo "FAIL: cannot download version 1"; exit 1; }

unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" index.properties > "${TEMPLATE_WORK_DIR}/index.properties"
unzip -p "${TEMPLATE_WORK_DIR}/catalog-v1.zip" "${TEMPLATE_PROPS_PATH}" > "${TEMPLATE_WORK_DIR}/service.properties"

grep -q "^catalog.name=${TEMPLATE_CATALOG}$" "${TEMPLATE_WORK_DIR}/index.properties" \
  && echo "PASS: catalog.name is ${TEMPLATE_CATALOG}" || echo "FAIL: catalog.name was not set"
grep -q "^catalog.properties\." "${TEMPLATE_WORK_DIR}/index.properties" \
  && echo "FAIL: catalog.properties.* is still declared (the package is still a template)" \
  || echo "PASS: catalog.properties.* removed"

grep -v '^[[:space:]]*#' "${TEMPLATE_PROPS_FILE}" | grep '=' | cut -d= -f1 | while read -r KEY; do
  LINE=$(grep "^${KEY}=" "${TEMPLATE_WORK_DIR}/service.properties")
  if [ -z "${LINE}" ]; then
    echo "FAIL: ${KEY} missing in the catalog"
  elif echo "${LINE}" | grep -q '{{'; then
    echo "FAIL: ${KEY} still has a placeholder: ${LINE}"
  else
    echo "PASS: ${KEY} has the supplied value"
  fi
done
```

**Expected result:**

- The output includes `PASS: version 1 downloaded`.
- The output includes `PASS: catalog.name is <TEMPLATE_CATALOG>`.
- The output includes `PASS: catalog.properties.* removed`.
- For every supplied property key, the output includes `PASS: <KEY> has the supplied value`.
- The output contains no line starting with `FAIL:`.
- The commands exit with code 0.

### Test C.4: A second instantiation creates a new version

```bash run
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "${TEMPLATE_CATALOG}" > /dev/null 2>&1 \
  && java -jar "${CLI_JAR}" service versions download --host "${WANAKU_BARN_URL}" --no-auth --plain \
    --name "${TEMPLATE_CATALOG}" --version 2 --output "${TEMPLATE_WORK_DIR}/catalog-v2.zip" > /dev/null 2>&1 \
  && echo "PASS: second instantiation created version 2" || echo "FAIL: version 2 not created"
```

**Expected result:**

- The output includes `PASS: second instantiation created version 2`.
- The output contains no line starting with `FAIL:`.

### Test C.5: Instantiation of an unknown template fails (negative)

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name does-not-exist-99999 --property key=value 2>&1)
EXIT_CODE=$?
[ "${EXIT_CODE}" -ne 0 ] && echo "${OUTPUT}" | grep -q "does not exist" \
  && echo "PASS: unknown template rejected" || echo "FAIL: unknown template not rejected (exit ${EXIT_CODE})"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${WANAKU_BARN_URL}/api/v1/service-template/instantiate" \
  -H "Content-Type: application/json" -d '{"templateName": "does-not-exist-99999", "properties": {}}')
[ "${HTTP_CODE}" = "404" ] && echo "PASS: REST returns 404 for an unknown template" \
  || echo "FAIL: expected 404, got ${HTTP_CODE}"
```

**Expected result:**

- The output includes `PASS: unknown template rejected`.
- The output includes `PASS: REST returns 404 for an unknown template`.
- The output contains no line starting with `FAIL:`.

### Test C.6: An invalid service name is rejected (negative)

```bash run
java -jar "${CLI_JAR}" service template instantiate --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_NAME}" --properties-from "${TEMPLATE_PROPS_FILE}" \
  --service-name "invalid name/../x" > /dev/null 2>&1
[ $? -ne 0 ] && echo "PASS: invalid service name rejected" || echo "FAIL: invalid service name accepted"
```

**Expected result:**

- The output includes `PASS: invalid service name rejected`.
- The output contains no line starting with `FAIL:`.

## Phase D: Tool registration in Wanaku Server

Run this section after [start-cic.md](start-cic.md) with `CIC_CATALOG="${TEMPLATE_CATALOG}"` and
`CIC_CATALOG_SYSTEM="${TEMPLATE_SYSTEM}"`. `CIC_NAME` and `CIC_NAMESPACE` must be set.

These rules apply:

- Wanaku Server identifies a tool by its name only. Do not register two forwards that expose the same tool name, also
  not in different namespaces. The last discovery moves the tool to its forward and namespace.
- Wanaku Server adds an `x-request-id` property to the input schema of each tool (see the audit trail documentation
  of Wanaku Server). The checks below ignore it.
- List operations are not governed. A tool call is denied unless governance permits it. Plans that call the tool
  must start Wanaku Server with the test governance configuration (step 2b of [start-server.md](start-server.md),
  with `CIC_NAMESPACE` in `WANAKU_TEST_GOVERNED_NAMESPACES`). Without it, the call returns
  `No governance policy permits this action.` or `evaluator: no_match`.

### Test D.1: Wanaku Server discovered the tool

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" tools list --host "${WANAKU_SERVER_URL}" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_TOOL}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_TOOL} is in the tool list" \
  || { echo "FAIL: ${TEMPLATE_TOOL} not in the tool list"; echo "${OUTPUT}"; }
```

**Expected result:**

- The output includes `PASS: <TEMPLATE_TOOL> is in the tool list`.
- The output contains no line starting with `FAIL:`.

### Test D.2: The tool has the declared parameters

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" tools show --host "${WANAKU_SERVER_URL}" --no-auth --plain "${TEMPLATE_TOOL}" 2>&1)
echo "${OUTPUT}"
echo "${OUTPUT}" | grep -q "mcp-forward" && echo "PASS: tool type is mcp-forward" || echo "FAIL: tool type"
for PARAM in ${TEMPLATE_TOOL_PARAMS}; do
  echo "${OUTPUT}" | grep -q "^${PARAM}[[:space:]]" \
    && echo "PASS: input schema has '${PARAM}'" || echo "FAIL: input schema has no '${PARAM}'"
done
```

**Expected result:**

- The output includes `PASS: tool type is mcp-forward`.
- For every parameter in `TEMPLATE_TOOL_PARAMS`, the output includes `PASS: input schema has '<PARAM>'`.
- The output contains no line starting with `FAIL:`.

### Test D.3: MCP clients see the tool in the namespace of the forward

```bash run
OUTPUT=$(java -jar "${CLI_JAR}" mcp tool list --uri "${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" --no-auth --plain 2>&1)
echo "${OUTPUT}" | grep -q "^${TEMPLATE_TOOL}[[:space:]]" \
  && echo "PASS: ${TEMPLATE_TOOL} is listed at ${WANAKU_MCP_BASE_URL}/${CIC_NAMESPACE}/mcp" \
  || { echo "FAIL: ${TEMPLATE_TOOL} not listed on the MCP endpoint"; echo "${OUTPUT}"; }
```

**Expected result:**

- The output includes `PASS: <TEMPLATE_TOOL> is listed at <WANAKU_MCP_BASE_URL>/<CIC_NAMESPACE>/mcp`.
- The output contains no line starting with `FAIL:`.

## Phase E: Cleanup

### Step E.1: Remove the template catalog

Barn does not delete a removed catalog. It keeps the catalog and its versions, and it reserves the name: a new deploy
or instantiation with the same name returns HTTP 409 until you run `wanaku service restore`. A fresh Barn home per
run (see [start-barn.md](start-barn.md)) avoids this.

```bash run
java -jar "${CLI_JAR}" service catalog remove --host "${WANAKU_BARN_URL}" --no-auth --plain \
  --name "${TEMPLATE_CATALOG}" > /dev/null 2>&1 || true
java -jar "${CLI_JAR}" service catalog list --host "${WANAKU_BARN_URL}" --no-auth --plain 2>&1 \
  | grep -q "^${TEMPLATE_CATALOG}[[:space:]]" \
  && echo "FAIL: ${TEMPLATE_CATALOG} is still listed" || echo "PASS: ${TEMPLATE_CATALOG} removed"
rm -rf "${TEMPLATE_WORK_DIR}"
```

**Expected result:**

- The output includes `PASS: <TEMPLATE_CATALOG> removed`.
- The output contains no line starting with `FAIL:`.
