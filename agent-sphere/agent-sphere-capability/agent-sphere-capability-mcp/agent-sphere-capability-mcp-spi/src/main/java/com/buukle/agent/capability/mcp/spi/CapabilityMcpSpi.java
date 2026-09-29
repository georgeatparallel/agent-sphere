package com.buukle.agent.capability.mcp.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpTestResultVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;

import java.time.LocalDateTime;
import java.util.List;

public interface CapabilityMcpSpi {
    McpVO createMcp(CreateMcpDTO dto);

    McpVO getMcp(Long id);

    List<McpVO> listMcps(String keyword, LocalDateTime startTime, LocalDateTime endTime);

    IPage<McpVO> pageMcps(int page, int size, String keyword, LocalDateTime startTime, LocalDateTime endTime);

    McpVO updateMcp(Long id, CreateMcpDTO dto);

    void deleteMcp(Long id);

    void batchDeleteMcp(java.util.List<Long> ids);

    List<McpVO> listMcpsByIds(List<Long> ids);

    String executeTool(String serverUrl, String toolName, String argumentsJson);

    /**
     * 带任务级凭证的工具调用。
     *
     * <p>凭证会被注入为 {@code X-Task-Mcp-Credential} 请求头，让 MCP 服务端识别本次调用归属
     * （Bole 用它区分「哪个用户的哪个寻访任务」）。为 null 时与三参版本完全等价。
     *
     * <p>新增重载而非改三参签名：既有实现方与调用方（ToolExecutor 之外的路径）无需改动。
     */
    default String executeTool(String serverUrl, String toolName, String argumentsJson, String taskMcpCredential) {
        return executeTool(serverUrl, toolName, argumentsJson);
    }

    /**
     * Discover tools from an MCP server at runtime.
     * Implemented in Phase 5 with full MCP protocol client.
     * Returns empty list if not yet implemented or if discovery fails.
     */
    default List<McpToolInfoVO> listMcpTools(Long mcpId) {
        return List.of();
    }

    /**
     * 连接测试：对登记的服务端做 initialize 握手，返回可达性、协议版本与工具数。
     */
    McpTestResultVO testConnection(Long mcpId);

    /**
     * 按 mcpId 直接调用该 MCP 的某个工具（用于前端「试调用」）。
     * serverUrl 版本见 {@link #executeTool(String, String, String)}。
     */
    String callToolByMcpId(Long mcpId, String toolName, String argumentsJson);
}
