# Load tests

k6 scripts that measure the MCP request path of Wanaku Server: an MCP client calls Wanaku, and Wanaku forwards the
call to an upstream MCP server. These scripts moved here from wanaku-barn, which does not serve MCP.

| Script | What each iteration does |
|--------|--------------------------|
| `mcp-tools-invoke.js` | Pings, lists the tools, and calls one tool |
| `mcp-resources-read.js` | Pings, lists the resources, and reads one resource |

Each iteration opens a new Streamable HTTP client, so the results include the MCP session setup.

## Prerequisites

- k6 built with the [xk6-mcp](https://github.com/grafana/xk6-mcp) extension:

  ```shell
  go install go.k6.io/xk6/cmd/xk6@latest
  xk6 build --with github.com/grafana/xk6-mcp
  ```

- A running Wanaku Server. Use a release build (`cargo build --release`) for meaningful numbers.
- An upstream MCP server, added to Wanaku as a forward. The defaults of the scripts match the
  [test MCP server fixture](../fixtures/test-mcp-server/README.md).

## Run the tests

1. Build and start the test MCP server (it listens on port 8181):

   ```shell
   cd fixtures/test-mcp-server
   ./mvnw clean install -B
   java -jar target/quarkus-app/quarkus-run.jar
   ```

2. Add it to Wanaku as a forward:

   ```shell
   wanaku forwards add --name perf --service http://localhost:8181/mcp --namespace default --no-auth
   ```

3. Run the scripts:

   ```shell
   ./k6 run --vus 10 --duration 30s load/mcp-tools-invoke.js
   ./k6 run --vus 10 --duration 30s load/mcp-resources-read.js
   ```

To measure the overhead of Wanaku, run the same script against the upstream server directly
(`WANAKU_MCP_URL=http://localhost:8181/mcp`) and compare the results.

## Configuration

Set these environment variables with `-e` or in the shell:

| Variable | Default | Description |
|----------|---------|-------------|
| `WANAKU_MCP_URL` | `http://localhost:8081/default/mcp` | The MCP endpoint of the namespace |
| `TOOL_NAME` | `restartService` | The tool that `mcp-tools-invoke.js` calls |
| `TOOL_ARGS` | `{"serverId": "srv-1", "service": "nginx"}` | The tool arguments, as JSON |
| `RESOURCE_URI` | `config://policies/safety-limits` | The resource that `mcp-resources-read.js` reads |

For example:

```shell
./k6 run -e WANAKU_MCP_URL=http://localhost:8081/ops/mcp --vus 50 --duration 1m load/mcp-tools-invoke.js
```

## Compare two builds

`generate-perf-report.py` compares the k6 summaries of a baseline build and a patched build, for example `main` and a
feature branch.

1. Run each script at the same VU levels and duration against each build. Save each summary with
   `--summary-export`, in this layout:

   ```text
   $EVAL_DIR/
   ├── baseline/
   │   ├── tools-invoke/
   │   │   ├── test-summary-vus-1.json
   │   │   ├── test-summary-vus-10.json
   │   │   └── vmstat-baseline-tools-invoke.log    # optional
   │   └── resources-read/
   │       └── test-summary-vus-*.json
   └── patched/
       ├── tools-invoke/
       │   └── test-summary-vus-*.json
       └── resources-read/
           └── test-summary-vus-*.json
   ```

   For example:

   ```shell
   ./k6 run --vus 10 --duration 30s \
     --summary-export "$EVAL_DIR/baseline/tools-invoke/test-summary-vus-10.json" \
     load/mcp-tools-invoke.js
   ```

2. Generate the report:

   ```shell
   python3 load/generate-perf-report.py --eval-dir "$EVAL_DIR" --test-scope all
   ```

   Options:

   - `--eval-dir` (required): the directory that contains `baseline/` and `patched/`.
   - `--test-scope`: `all` (default), `tools`, or `resources`.
   - `--output`: the output file. The default is `$EVAL_DIR/perf-report.md`.

The report compares these metrics for each VU level:

| Metric | Better when | Description |
|--------|-------------|-------------|
| `mcp_request_duration` (avg, med, p90, p95, max) | Lower | MCP request latency |
| `mcp_request_count` (rate, count) | Higher | Throughput |
| `mcp_request_errors` (rate, count) | Lower | Failed requests |
| `iterations` (rate, count) | Higher | Iteration throughput |
| `iteration_duration` (avg, p95) | Lower | Time of one iteration, with all its MCP calls |

A green circle marks an improvement of more than 5%. A red circle marks a regression of more than 10%.

## Troubleshooting

- **The tool is not listed:** make sure that the forward is in the namespace of `WANAKU_MCP_URL`, with
  `wanaku tools list`.
- **Latency grows at high VU counts:** each iteration opens a new MCP session. At high concurrency, session setup
  and connection queuing dominate the p95.
