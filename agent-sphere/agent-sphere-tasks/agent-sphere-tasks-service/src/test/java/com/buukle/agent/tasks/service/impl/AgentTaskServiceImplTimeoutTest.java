package com.buukle.agent.tasks.service.impl;

import com.buukle.agent.common.config.AgentRuntimeProperties;
import com.buukle.agent.common.mcp.TaskMcpCredentialStore;
import com.buukle.agent.instance.spi.AgentLlmInteractionRecordSpi;
import com.buukle.agent.instance.spi.AgentToolCallRecordSpi;
import com.buukle.agent.instance.spi.InstanceSpi;
import com.buukle.agent.instance.spi.RunSpi;
import com.buukle.agent.instance.spi.SessionSpi;
import com.buukle.agent.runtime.orchestration.service.ChatRuntimeService;
import com.buukle.agent.sso.spi.SsoIdentitySpi;
import com.buukle.agent.tasks.repository.AgentTaskArtifactMapper;
import com.buukle.agent.tasks.repository.AgentTaskMapper;
import com.buukle.agent.tasks.service.TaskCallbackService;
import com.buukle.agent.tasks.service.TaskContractValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 任务超时的取值口径：业务方指定优先，未指定才回落到 AS 侧配置默认。
 *
 * <p>这条边界很关键 —— 该值同时决定任务存活上限与业务方签发 MCP 凭证的 TTL。
 * 若把「任务级指定」错误地忽略成默认值，长任务会在凭证 TTL 之后仍在运行，
 * 中途 MCP 调用全部被拒（且表现为与真正根因相隔很远的「缺少凭证」）。
 */
@ExtendWith(MockitoExtension.class)
class AgentTaskServiceImplTimeoutTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private AgentTaskArtifactMapper artifactMapper;
    @Mock
    private AgentRuntimeProperties runtimeProperties;
    @Mock
    private InstanceSpi instanceSpi;
    @Mock
    private SessionSpi sessionSpi;
    @Mock
    private RunSpi runSpi;
    @Mock
    private ChatRuntimeService chatRuntimeService;
    @Mock
    private TaskCallbackService taskCallbackService;
    @Mock
    private SsoIdentitySpi ssoIdentitySpi;
    @Mock
    private TaskContractValidator contractValidator;
    @Mock
    private AgentLlmInteractionRecordSpi llmInteractionRecordSpi;
    @Mock
    private AgentToolCallRecordSpi toolCallRecordSpi;
    @Mock
    private TaskMcpCredentialStore taskMcpCredentialStore;

    @InjectMocks
    private AgentTaskServiceImpl service;

    private void setDefaultMaxPollSeconds(long value) throws Exception {
        Field field = AgentTaskServiceImpl.class.getDeclaredField("defaultMaxPollSeconds");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    @Test
    void fallsBackToConfiguredDefaultWhenTaskHasNoTimeout() throws Exception {
        setDefaultMaxPollSeconds(3600L);

        assertEquals(3600L, service.effectiveTimeoutSeconds(null));
    }

    @Test
    void taskLevelTimeoutOverridesConfiguredDefault() throws Exception {
        setDefaultMaxPollSeconds(3600L);

        assertEquals(120L, service.effectiveTimeoutSeconds(120));
    }
}
