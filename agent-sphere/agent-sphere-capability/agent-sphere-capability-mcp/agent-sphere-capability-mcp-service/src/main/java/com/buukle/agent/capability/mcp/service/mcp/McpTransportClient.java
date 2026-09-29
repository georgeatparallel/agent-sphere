package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;

import java.util.List;

/**
 * MCP transport client that handles the MCP protocol lifecycle:
 * initialize -> tools/list -> tools/call.
 * Supports Streamable HTTP (current spec) and legacy HTTP+SSE transport.
 */
public interface McpTransportClient extends AutoCloseable {

    /**
     * Initialize MCP session (handshake + capability negotiation).
     */
    void initialize();

    /**
     * Discover available tools from the MCP server.
     */
    List<McpToolInfoVO> listTools();

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName      the tool name as defined by the server
     * @param argumentsJson JSON arguments as a string
     * @return the tool result as a string
     */
    String callTool(String toolName, String argumentsJson);

    /**
     * 带调用上下文的工具调用：附加请求头（如任务级凭证）在这一次请求上生效。
     *
     * <p>默认实现忽略上下文，保证既有实现方无需改动；需要注入任务凭证的传输实现覆写本方法。
     */
    default String callTool(String toolName, String argumentsJson, McpCallContext callContext) {
        return callTool(toolName, argumentsJson);
    }

    /**
     * Check if the client is still connected/initialized.
     */
    boolean isConnected();

    /**
     * 握手后协商的 MCP 协议版本；未初始化返回 null。
     */
    default String negotiatedProtocolVersion() {
        return null;
    }
}
