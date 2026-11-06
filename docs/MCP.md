# MCP servers

Sebastian can use the tools of remote MCP servers during a call. Sebastian connects over Streamable HTTP when the call starts, lists each server's tools and offers them to the LLM alongside the built-in ones. Say "Sebastian, ..." and Sebastian picks the right tool.

Add servers to `sebastian.json`, then run `python bridge/push_config.py`:

```json
"mcp_servers": [
  { "name": "crm", "url": "https://mcp.example.com/mcp", "token_env": "CRM_MCP_TOKEN" }
]
```

`token_env` names a variable in `.env`, so tokens stay out of the repo. Leave it out for a server with no auth.

Works with: remote servers (`https://`, Streamable HTTP) with no auth or a bearer token.

Doesn't work with: servers that need OAuth, local stdio servers.

The client is `app/src/main/java/dev/callstream/probe/shell/SebastianMcp.kt`. A server that's down is skipped and the call carries on without it.
