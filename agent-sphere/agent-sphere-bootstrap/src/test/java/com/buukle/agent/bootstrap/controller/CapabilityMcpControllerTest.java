package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.capability.mcp.controller.CapabilityMcpController;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.service.CapabilityMcpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CapabilityMcpControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    CapabilityMcpService capabilityMcpService;

    @InjectMocks
    CapabilityMcpController capabilityMcpController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(capabilityMcpController).build();
    }

    @Test
    void create_shouldReturn201() throws Exception {
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName("My MCP Server");
        dto.setDescription("A test MCP server");
        dto.setServerUrl("http://localhost:9090");
        dto.setServerType("sse");
        McpVO vo = new McpVO();
        vo.setId(1L);
        vo.setName("My MCP Server");
        vo.setDescription("A test MCP server");
        vo.setServerUrl("http://localhost:9090");
        vo.setServerType("sse");
        vo.setStatus("ENABLED");
        vo.setCreatedAt("2026-05-30 10:00:00");
        given(capabilityMcpService.createMcp(any(CreateMcpDTO.class))).willReturn(vo);

        mockMvc.perform(post("/api/v1/capability/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("My MCP Server"))
                .andExpect(jsonPath("$.serverUrl").value("http://localhost:9090"))
                .andExpect(jsonPath("$.serverType").value("sse"));
    }

    @Test
    void testConnection_shouldReturnResult() throws Exception {
        com.buukle.agent.capability.mcp.dtvo.vo.McpTestResultVO vo =
                com.buukle.agent.capability.mcp.dtvo.vo.McpTestResultVO.builder()
                        .ok(true)
                        .serverType("http")
                        .protocolVersion("2025-11-25")
                        .toolCount(2)
                        .message("连接成功")
                        .build();
        given(capabilityMcpService.testConnection(1L)).willReturn(vo);

        mockMvc.perform(post("/api/v1/capability/mcp/1/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.toolCount").value(2))
                .andExpect(jsonPath("$.protocolVersion").value("2025-11-25"));
    }

    @Test
    void listTools_shouldReturnTools() throws Exception {
        given(capabilityMcpService.listMcpTools(1L)).willReturn(java.util.List.of(
                com.buukle.agent.capability.mcp.dtvo.vo.McpToolInfoVO.builder()
                        .name("search").description("search docs").inputSchema("{}").build()));

        mockMvc.perform(get("/api/v1/capability/mcp/1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("search"))
                .andExpect(jsonPath("$[0].description").value("search docs"));
    }

    @Test
    void callTool_shouldPassArgs() throws Exception {
        given(capabilityMcpService.callToolByMcpId(eq(1L), eq("search"), anyString()))
                .willReturn("{\"ok\":true}");

        mockMvc.perform(post("/api/v1/capability/mcp/1/tools/search/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
