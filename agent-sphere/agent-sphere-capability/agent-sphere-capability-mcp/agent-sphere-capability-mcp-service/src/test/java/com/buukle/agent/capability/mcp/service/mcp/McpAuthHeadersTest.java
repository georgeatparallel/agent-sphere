package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * authConfig 解析：合法则给出鉴权头，非法则**显式报错**（而不是静默不带鉴权）。
 *
 * <p>这组断言直接对应一次真实事故：解析失败只打 warn 就继续，请求不带 Authorization 发出去，
 * 下游报「缺少 Authorization / 401」，而根因在 AS 的登记配置 —— 排查方向被带偏得很远。
 * 另外那条 warn 还把 authConfig 原文（含令牌）写进了日志。
 */
class McpAuthHeadersTest {

    @Test
    void blankAuthConfigYieldsNoHeaders() {
        // 空白是合法的「不需要鉴权」，不能报错，但也意味着请求不会带任何鉴权头。
        assertEquals(Map.of(), McpAuthHeaders.parse(null));
        assertEquals(Map.of(), McpAuthHeaders.parse(""));
        assertEquals(Map.of(), McpAuthHeaders.parse("   "));
    }

    @Test
    void parsesHeaderMap() {
        Map<String, String> headers = McpAuthHeaders.parse(
                "{\"Authorization\":\"Bearer svc-token\",\"X-Tenant\":\"bole\"}");

        assertEquals("Bearer svc-token", headers.get("Authorization"));
        assertEquals("bole", headers.get("X-Tenant"));
        assertEquals(2, headers.size());
    }

    @Test
    void invalidJsonFailsLoudly() {
        BizException e = assertThrows(BizException.class, () -> McpAuthHeaders.parse("not-json"));
        assertEquals(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode(), e.getErrorCode());
        // 必须给出形状提示，否则使用者不知道该填什么
        assertTrue(e.getMessage().contains(McpAuthHeaders.EXPECTED_SHAPE), e.getMessage());
    }

    @Test
    void bareTokenIsRejectedWithHint() {
        // 最常见的误配：只粘贴了一串 token。此前会被静默忽略（不是 JSON 对象）。
        BizException e = assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("human-resource-intermediary-mcp-service-token-2024"));
        assertEquals(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID.getCode(), e.getErrorCode());
        assertTrue(e.getMessage().contains("Bearer"), e.getMessage());
    }

    @Test
    void nonScalarValueIsRejected() {
        assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("{\"Authorization\":{\"token\":\"x\"}}"));
    }

    @Test
    void errorMessageMustNotLeakTheRawValue() {
        // 脱敏要求：异常消息里不得回显 authConfig 原文（可能含令牌）。
        String secret = "super-secret-token-value-12345";
        BizException e = assertThrows(BizException.class,
                () -> McpAuthHeaders.parse("{\"Authorization\":\"" + secret + "\", \"bad\": {} }"));

        assertFalse(e.getMessage().contains(secret), e.getMessage());
    }

    @Test
    void blankHeaderNameIsRejected() {
        assertThrows(BizException.class, () -> McpAuthHeaders.parse("{\"  \":\"v\"}"));
    }
}
