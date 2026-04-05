package com.travel.agent;

import com.travel.agent.core.tools.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TravelAgentApplication {

    /**
     * Spring AI 自动配置可能已提供；若缺失则在此创建，供意图识别与 ReAct 注入。
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.Builder.class)
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * 示例：通过 Bean 方式注册 {@link ToolRegistry.DiscoverableTravelTool}，由 {@link com.travel.agent.core.tools.ToolRegistry} 自动发现。
     */
    @Bean
    public ToolRegistry.DiscoverableTravelTool expenseHintTool() {
        return new ToolRegistry.DiscoverableTravelTool() {
            @Override
            public String toolName() {
                return "expense_hint";
            }

            @Override
            public String execute(String input) {
                return "报销材料通常包括：电子发票、行程单、支付凭证；请注意抬头与税号一致性。";
            }
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
    }
}
