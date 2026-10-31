import { check } from 'k6';
import mcp from 'k6/x/mcp';

// The MCP endpoint of a Wanaku namespace, and a resource that a forward in that namespace serves.
const MCP_URL = __ENV.WANAKU_MCP_URL || 'http://localhost:8081/default/mcp';
const RESOURCE_URI = __ENV.RESOURCE_URI || 'config://policies/safety-limits';

export default function () {
    const client = new mcp.StreamableHTTPClient({ base_url: MCP_URL, timeout: 5 });

    check(client.ping(), { 'ping succeeds': (ok) => ok });

    const resources = client.listAllResources().resources;
    check(resources, { 'resource is listed': (list) => list.some((resource) => resource.uri === RESOURCE_URI) });

    const content = client.readResource({ uri: RESOURCE_URI });
    check(content, { 'resource has content': (c) => c.contents.length > 0 });
}
