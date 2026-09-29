package com.buukle.agent.capability.mcp.service.mcp;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次 MCP 调用的附加上下文（当前只有任务级凭证）。
 *
 * <p>做成独立对象而不是往 transport 上加可变字段：transport 客户端是按 mcpId 缓存复用的
 * （见 {@code McpTransportFactory}），把凭证挂在实例上会让并发任务的凭证互相覆盖 ——
 * 那等于把「谁的收藏记录」搞串，是比不去重严重得多的故障。
 */
@Data
public class McpCallContext {

    private static final McpCallContext NONE = new McpCallContext(Collections.emptyMap());

    private final Map<String, String> extraHeaders;

    private McpCallContext(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    public static McpCallContext none() {
        return NONE;
    }

    /** 携带任务级凭证；凭证为空时返回 none，避免往请求里塞一个空头。 */
    public static McpCallContext ofTaskCredential(String taskCredential) {
        if (taskCredential == null || taskCredential.isBlank()) {
            return NONE;
        }
        Map<String, String> headers = new LinkedHashMap<>(1);
        headers.put(McpProtocolConstants.HEADER_TASK_MCP_CREDENTIAL, taskCredential.trim());
        return new McpCallContext(Collections.unmodifiableMap(headers));
    }

    public boolean isEmpty() {
        return extraHeaders.isEmpty();
    }

    public String header(String name) {
        return extraHeaders.get(name);
    }
}
