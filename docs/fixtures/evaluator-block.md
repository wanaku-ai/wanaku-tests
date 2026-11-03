# Deterministic evaluator block fixture

`block.wat` implements Wanaku's `evaluator-action` component interface. Every
evaluation calls `wanaku:evaluator/response.block` with the literal message
`mcp-cli-evaluator-block`. Use it with `engine.type: passthrough`; it needs no
LLM, network service, SDK build, or credentials.

The component adapts the response fixture from
[`wanaku-ai/wanaku`](https://github.com/wanaku-ai/wanaku/blob/main/features/evaluator/tests/fixtures/response.wat).
Its exported context matches `features/evaluator/wit/evaluator.wit`.

The MCP CLI plan embeds the compiled component as base64 so the Saci worker
does not need a compiler or repository checkout. Regenerate that payload with
[`wasm-tools`](https://github.com/bytecodealliance/wasm-tools) (validated with 1.262.0):

```bash
wasm-tools parse fixtures/evaluator-block/block.wat -o /tmp/mcp-evaluator-block.wasm
wasm-tools validate /tmp/mcp-evaluator-block.wasm
base64 < /tmp/mcp-evaluator-block.wasm | tr -d '\n'
```

Replace the `base64 -d` heredoc in Test 5.6 of `plans/mcp-cli-commands.md` after
changing this fixture. The plan creates its WASM file in the owned server's
temporary directory and removes it after restoring the evaluator configuration.
