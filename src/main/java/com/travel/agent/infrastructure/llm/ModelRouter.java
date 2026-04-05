package com.travel.agent.infrastructure.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 多模型路由：按业务域优先级选择模型，并结合熔断状态降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final CircuitBreaker circuitBreaker;

    @Value("${travel.agent.models.primary}")
    private String primaryModel;

    @Value("${travel.agent.models.fallback}")
    private String fallbackModel;

    public ChatClient selectChatClient(ChatClient.Builder chatClientBuilder, String mode) {
        List<ModelCandidate> order = buildPriority(mode);
        order.sort(Comparator.comparingInt(ModelCandidate::priority));

        for (ModelCandidate cand : order) {
            String breakerKey = "model-" + cand.modelName();
            if (!circuitBreaker.allow(breakerKey)) {
                log.debug("skip {} (breaker open)", cand.modelName());
                continue;
            }
            log.debug("route mode={} -> model={} (pri={})", mode, cand.modelName(), cand.priority());
            return chatClientBuilder
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(cand.modelName())
                            .temperature(0.35)
                            .maxTokens(4096)
                            .build())
                    .build();
        }
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(fallbackModel)
                        .temperature(0.35)
                        .maxTokens(4096)
                        .build())
                .build();
    }

    private List<ModelCandidate> buildPriority(String mode) {
        List<ModelCandidate> list = new ArrayList<>();
        String m = mode == null ? "general" : mode.toLowerCase(Locale.ROOT);
        switch (m) {
            case "policy", "booking" -> {
                list.add(new ModelCandidate(primaryModel, 1));
                list.add(new ModelCandidate(fallbackModel, 5));
            }
            case "expense", "rag" -> {
                list.add(new ModelCandidate(primaryModel, 2));
                list.add(new ModelCandidate(fallbackModel, 4));
            }
            default -> {
                list.add(new ModelCandidate(fallbackModel, 1));
                list.add(new ModelCandidate(primaryModel, 3));
            }
        }
        return list;
    }

    private record ModelCandidate(String modelName, int priority) {
    }
}
