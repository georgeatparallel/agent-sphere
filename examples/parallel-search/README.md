# Parallel Search MCP

[中文](README-cn.md)

This optional example connects AgentSphere's existing HTTP MCP capability to
[Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp).
The `/mcp` endpoint offers anonymous `web_search` and `web_fetch` for exploration
and light use. No Parallel API key is needed; anonymous usage has lower rate
limits. AgentSphere authentication is still required. Model inference is separate.
Existing capabilities and model/provider defaults are unchanged.

Start AgentSphere using the repository's [quick start](../../QUICK_START.md).
You need an AgentSphere account with MCP create/test permissions, curl, and jq.
Run these commands from the repository root against your own running backend:

```bash
export AGENT_SPHERE_URL=http://localhost:8080
export AGENT_SPHERE_TOKEN='<your AgentSphere login token>'

# Create a new capability. Save the returned id; rerunning creates another entry.
MCP_ID=$(curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp" \
  -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/parallel-search/mcp.json | jq -er '.id')

# Discover the current tools and their input schemas.
curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools" \
  -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" | jq

# Reuse one random session id for related search/fetch calls.
PARALLEL_SESSION_ID=$(cat /proc/sys/kernel/random/uuid) # Linux; on macOS use uuidgen
jq -n --arg sid "$PARALLEL_SESSION_ID" \
  '{objective:"Find the official MCP transport documentation", search_queries:["MCP Streamable HTTP transport documentation"], session_id:$sid}' \
  | curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools/web_search/call" \
      -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- | jq

# Fetch a specific page when search excerpts are insufficient.
jq -n --arg sid "$PARALLEL_SESSION_ID" \
  '{urls:["https://modelcontextprotocol.io/specification/2025-11-25/basic/transports"], objective:"Explain Streamable HTTP transport", session_id:$sid}' \
  | curl --fail-with-body -sS "$AGENT_SPHERE_URL/api/v1/capability/mcp/$MCP_ID/tools/web_fetch/call" \
      -H "Authorization: Bearer $AGENT_SPHERE_TOKEN" \
      -H 'Content-Type: application/json' --data-binary @- | jq
```

Results use MCP's `content` array; inspect `isError` even when the AgentSphere
HTTP response is successful. Search results include source URLs and excerpts;
fetch returns extracted page content. Respect rate-limit errors and retry later.
The commands exercise the direct capability API and do not require an LLM.

In **Capabilities → MCP**, the equivalent fields are the values in `mcp.json`:
server type `http` selects Streamable HTTP (not legacy SSE). `authConfig` is a
JSON **string** in the API payload; in the UI's header configuration field, enter
`{"User-Agent":"agent-sphere/1.0.0"}`. This identifies AgentSphere's outgoing
MCP requests without adding an Authorization header to Parallel. Your
AgentSphere login token authenticates requests to your backend only.
