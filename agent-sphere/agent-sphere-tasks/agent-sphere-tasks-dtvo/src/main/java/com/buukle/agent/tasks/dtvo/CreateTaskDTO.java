package com.buukle.agent.tasks.dtvo;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class CreateTaskDTO implements Serializable {
    @NotBlank(message = "goal can't be blank")
    @Size(min = 1, max = 5000)
    private String goal;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> context;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> expectedOutput;
    @JsonDeserialize(using = LenientMapDeserializer.class)
    private Map<String, Object> config;
    @Size(max = 500)
    private String callbackUrl;
    @Size(max = 64)
    private String code;
    @Size(max = 512)
    private String subject;
    @NotBlank(message = "businessType can't be blank")
    @Size(max = 64)
    private String businessType;

    /**
     * 任务级 MCP 凭证：调用方（Bole）在提交任务时下发，Agent 执行 MCP 工具时作为
     * {@code X-Task-Mcp-Credential} 请求头注入，让服务端能识别「这次调用属于哪个任务/哪个用户」。
     *
     * <p>必须是顶层独立字段：{@code config} 会被整包序列化进 prompt（凭证进 prompt 等于交给模型保管），
     * {@code context} 会随 TaskVO 对外返回。凭证只在提交时进 Redis，运行期由工具执行链路取用，
     * 既不落库也不进任何模型可见文本。
     */
    @Size(max = 4096)
    private String mcpCredential;
}
