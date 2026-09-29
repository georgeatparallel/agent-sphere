package com.buukle.agent.capability.mcp.exception;

import com.buukle.agent.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CapabilityMcpErrorCode implements ErrorCode {
    MCP_NOT_FOUND("A0021", "MCP工具不存在", "请检查MCP ID"),
    MCP_SERVER_UNREACHABLE("C0002", "MCP Server不可达", "MCP服务器连接失败，请稍后重试"),
    MCP_EXECUTE_FAILED("C0003", "MCP工具调用失败", "工具执行出错，请稍后重试"),
    MCP_LIST_TOOLS_FAILED("C0004", "MCP获取工具列表失败", "无法从服务器拉取工具列表"),
    MCP_CONNECT_TEST_FAILED("C0005", "MCP连接测试失败", "测试连接未通过"),
    MCP_AUTH_CONFIG_INVALID("C0006", "MCP认证配置无效", "authConfig 需为 JSON 对象，如 {\"Authorization\":\"Bearer <token>\"}");

    private final String code;
    private final String message;
    private final String userTip;
}
