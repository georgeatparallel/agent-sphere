package com.buukle.agent.capability.mcp.service.mcp;

import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * authConfig（登记时配置的出站鉴权头）的解析。
 *
 * <p>集中到一处，是因为两个 transport（Streamable / Legacy SSE）此前各抄了一份**同样有缺陷**的实现：
 * <ol>
 *   <li>解析失败只打一条 warn 就继续 —— 请求会**不带任何鉴权头**发出去，
 *       下游报的是「缺少 Authorization / 401」，而根因在 AS 的登记配置，排查方向被彻底带偏；</li>
 *   <li>那条 warn 把 authConfig **原文**打进日志 —— 里面装的正是令牌；</li>
 *   <li>值不是 JSON 对象（例如只粘贴了一串 token）时同样被静默忽略。</li>
 * </ol>
 *
 * <p>现在的语义：
 * <ul>
 *   <li>为空 → 合法的「不需要鉴权」，返回空表，但会打一条 WARN 提示（这类问题当初就是"太安静"才难查）；</li>
 *   <li>非法（解析失败 / 不是对象 / 值不是标量）→ **直接抛错**，并在消息里给出形状提示，**不回显原文**。</li>
 * </ul>
 */
@Slf4j
public final class McpAuthHeaders {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 形状提示里用的示例，帮助使用者写出正确格式。 */
    static final String EXPECTED_SHAPE = "{\"Authorization\":\"Bearer <token>\"}";

    private McpAuthHeaders() {
    }

    /**
     * 解析为待注入的请求头。空白返回空表；非法抛 {@link CapabilityMcpErrorCode#MCP_AUTH_CONFIG_INVALID}。
     * 结果不可变，可安全地长期持有（transport 按 mcpId 缓存复用）。
     */
    public static Map<String, String> parse(String authConfigJson) {
        if (authConfigJson == null || authConfigJson.isBlank()) {
            // 不报错：确实存在无需鉴权的 MCP Server。但必须留痕 —— 若对方其实要鉴权，
            // 这条 WARN 就是唯一能把「下游 401」和「本侧漏配」连起来的线索。
            log.warn("MCP authConfig 未配置：出站请求不携带任何鉴权头；若目标 MCP Server 需要鉴权，将返回鉴权相关错误");
            return Collections.emptyMap();
        }
        JsonNode auth;
        try {
            auth = JSON.readTree(authConfigJson);
        } catch (Exception e) {
            // 不回显原文（可能含令牌），只给出形状提示。
            throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                    "authConfig 不是合法 JSON，期望形如 " + EXPECTED_SHAPE);
        }
        if (auth == null || !auth.isObject()) {
            throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                    "authConfig 必须是 JSON 对象（键=请求头名，值=请求头值），期望形如 " + EXPECTED_SHAPE
                            + "；若只填了一串 token，请补上 {\"Authorization\":\"Bearer <token>\"} 结构");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Iterator<String> fields = auth.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            JsonNode value = auth.get(key);
            if (key == null || key.isBlank()) {
                throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                        "authConfig 存在空的请求头名，期望形如 " + EXPECTED_SHAPE);
            }
            if (value == null || !value.isValueNode()) {
                throw new BizException(CapabilityMcpErrorCode.MCP_AUTH_CONFIG_INVALID,
                        "authConfig 的值必须是字符串标量（" + key + " 不是），期望形如 " + EXPECTED_SHAPE);
            }
            headers.put(key, value.asText());
        }
        return Collections.unmodifiableMap(headers);
    }
}
