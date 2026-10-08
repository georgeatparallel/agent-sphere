# Parallel Search MCP

[English](README.md)

该可选示例通过 AgentSphere 已有的 HTTP MCP 能力连接
[Parallel Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)。
`https://search.parallel.ai/mcp` 支持匿名调用 `web_search` 和 `web_fetch`，
适合探索和轻量使用，无需 Parallel API key。匿名访问有较低的速率限制；
模型推理费用另计。现有能力和模型、供应商默认配置不变。

按[快速开始](../../QUICK_START-cn.md)启动自己的 AgentSphere 后端，准备具有
MCP 创建和测试权限的账号、curl 和 jq。在仓库根目录执行
[英文示例中的命令](README.md)：

1. 设置 `AGENT_SPHERE_URL` 和自己的登录令牌 `AGENT_SPHERE_TOKEN`。
2. 将 `mcp.json` POST 到 `/api/v1/capability/mcp`，保存返回的 `id`。
   重复创建会新增一条记录。
3. GET `/api/v1/capability/mcp/{id}/tools`，检查当前工具和输入 schema。
4. 调用 `/api/v1/capability/mcp/{id}/tools/web_search/call` 和
   `/api/v1/capability/mcp/{id}/tools/web_fetch/call`。
   相关搜索和抓取复用同一个随机 `session_id`。

也可在「能力 → MCP」页面按 `mcp.json` 填写：服务器类型为 `http`
（Streamable HTTP，不是旧版 SSE），请求头配置填写
`{"User-Agent":"agent-sphere/1.0.0"}`。API 的 `authConfig` 字段需要 JSON
字符串，示例已完成转义。该请求头标识出站 MCP 请求，不向 Parallel 添加
Authorization；AgentSphere 登录令牌仅用于认证自己的后端。

响应采用 MCP `content` 数组。即使 HTTP 状态成功，也要检查 `isError`。
搜索返回来源 URL 和摘要，抓取返回页面内容。遇到限流请稍后重试。
示例演示直接调用能力 API，无需 LLM。
