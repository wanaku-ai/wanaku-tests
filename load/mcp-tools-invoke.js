import { check } from 'k6';
import mcp from 'k6/x/mcp';

// The MCP endpoint of a Wanaku namespace, and a tool that a forward in that namespace serves.
const MCP_URL = __ENV.WANAKU_MCP_URL || 'http://localhost:8081/default/mcp';
const TOOL_NAME = __ENV.TOOL_NAME || 'restartService';
const TOOL_ARGS = JSON.parse(__ENV.TOOL_ARGS || '{"serverId": "srv-1", "service": "nginx"}');

export default function () {
    const client = new mcp.StreamableHTTPClient({ base_url: MCP_URL, timeout: 5 });

    check(client.ping(), { 'ping succeeds': (ok) => ok });

    const tools = client.listAllTools().tools;
    check(tools, { 'tool is listed': (list) => list.some((tool) => tool.name === TOOL_NAME) });

    const result = client.callTool({ name: TOOL_NAME, arguments: TOOL_ARGS });
    check(result, { 'tool call is not an error': (r) => !r.isError });
}
