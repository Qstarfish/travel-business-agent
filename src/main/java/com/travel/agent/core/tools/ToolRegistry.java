package com.travel.agent.core.tools;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 工具注册：启动时注册内置商旅工具；可选通过 MCP HTTP 端点转发。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final ApplicationContext applicationContext;

    private final Map<String, BiFunction<String, String, String>> tools = new LinkedHashMap<>();

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Value("${travel.agent.mcp.enabled:false}")
    private boolean mcpEnabled;

    @Value("${travel.agent.mcp.endpoint:http://localhost:8090/mcp}")
    private String mcpEndpoint;

    private WebClient mcpClient;

    @PostConstruct
    public void registerDefaults() {
        mcpClient = WebClient.builder()
                .baseUrl(mcpEndpoint)
                .build();

        tools.put("search_policy", (name, input) ->
                "命中差标片段：经济舱标准；酒店一线城市<=600元；需关联出差申请单。");
        tools.put("check_budget", (name, input) -> {
            if (input.matches(".*[5-9][0-9]{3}.*")) {
                return "预算校验：检测到可能超标金额，请提交审批。";
            }
            return "预算校验：在常见差标范围内。";
        });
        tools.put("itinerary_stub", (name, input) ->
                "已生成占位行程草案：去程直飞、返程高铁，待确认日期与审批。");

        discoverSpringTools();
        log.info("Registered {} tools (MCP enabled={})", tools.size(), mcpEnabled);
    }

    /**
     * 自动注册 Spring 容器中所有 {@link DiscoverableTravelTool} 实现，便于按模块扩展工具。
     */
    private void discoverSpringTools() {
        Map<String, DiscoverableTravelTool> beans = applicationContext.getBeansOfType(DiscoverableTravelTool.class);
        for (DiscoverableTravelTool bean : beans.values()) {
            String key = bean.toolName().toLowerCase(Locale.ROOT);
            tools.put(key, (n, input) -> bean.execute(input));
            log.info("Discovered tool bean: {}", key);
        }
    }

    /**
     * 供其它模块以 {@code @Component} 声明并自动挂载的工具契约。
     */
    public interface DiscoverableTravelTool {
        String toolName();

        String execute(String input);
    }

    public String describeTools() {
        StringBuilder sb = new StringBuilder();
        for (String name : tools.keySet()) {
            sb.append("- ").append(name).append(": 商旅领域工具\n");
        }
        if (mcpEnabled) {
            sb.append("- mcp_forward: 通过 MCP 网关调用外部工具\n");
        }
        return sb.toString();
    }

    public String invoke(String toolName, String input) {
        String key = toolName.toLowerCase(Locale.ROOT);
        if (mcpEnabled && "mcp_forward".equals(key)) {
            return forwardMcp(input);
        }
        BiFunction<String, String, String> fn = tools.get(key);
        if (fn == null) {
            return "未知工具: " + toolName + "，可用: " + tools.keySet();
        }
        return fn.apply(toolName, input);
    }

    private String forwardMcp(String payload) {
        try {
            String body = mcpClient.post()
                    .uri("/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("payload", payload))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            return body != null ? body : "(empty mcp response)";
        } catch (WebClientResponseException e) {
            log.warn("MCP HTTP error: {}", e.getStatusCode());
            return "[mcp http error] " + e.getStatusCode();
        } catch (Exception e) {
            log.warn("MCP forward failed: {}", e.getMessage());
            return "[mcp error] " + e.getMessage();
        }
    }
}
