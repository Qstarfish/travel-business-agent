package com.travel.agent.core.intent;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 快路径：规则与关键词；慢路径：轻量 LLM 分类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognizer {

    private static final Pattern POLICY = Pattern.compile(".*(差标|政策|报销|标准|舱位|酒店星级).*");
    private static final Pattern BOOKING = Pattern.compile(".*(订票|机票|航班|酒店|火车|高铁|行程|出行).*");
    private static final Pattern EXPENSE = Pattern.compile(".*(发票|费用|对账|预算).*");

    private final ChatClient.Builder chatClientBuilder;

    @Value("${travel.agent.intent.llm-fallback-enabled:true}")
    private boolean llmFallbackEnabled;

    public IntentResult recognize(String sessionId, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return IntentResult.builder().mode("general").confidence(0.5).source("empty").build();
        }
        String text = userInput.trim();
        IntentResult fast = fastLane(text);
        if (fast.getConfidence() >= 0.82) {
            return fast;
        }
        if (!llmFallbackEnabled) {
            return fast;
        }
        return slowLane(text, fast);
    }

    private IntentResult fastLane(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (POLICY.matcher(text).matches()) {
            return IntentResult.builder().mode("policy").confidence(0.9).source("rule").build();
        }
        if (BOOKING.matcher(text).matches()) {
            return IntentResult.builder().mode("booking").confidence(0.88).source("rule").build();
        }
        if (EXPENSE.matcher(text).matches()) {
            return IntentResult.builder().mode("expense").confidence(0.85).source("rule").build();
        }
        if (lower.contains("你好") || lower.contains("谢谢")) {
            return IntentResult.builder().mode("general").confidence(0.75).source("rule_greeting").build();
        }
        return IntentResult.builder().mode("general").confidence(0.45).source("rule_default").build();
    }

    private IntentResult slowLane(String text, IntentResult fastHint) {
        try {
            ChatClient client = chatClientBuilder.build();
            String prompt = """
                    你是商旅意图分类器。仅从以下标签中选一个输出，不要解释：booking, policy, expense, general, rag。
                    booking=预订交通住宿；policy=差标政策合规；expense=报销发票；general=闲聊或其它；rag=需要查知识库的事实问答。
                    用户说：%s
                    """.formatted(text);
            String raw = client.prompt().user(prompt).call().content();
            String tag = raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            String mode = mapTag(tag);
            return IntentResult.builder()
                    .mode(mode)
                    .confidence(0.72)
                    .source("llm")
                    .rawModelOutput(raw)
                    .build();
        } catch (Exception e) {
            log.warn("LLM intent failed, using fast hint: {}", e.getMessage());
            return IntentResult.builder()
                    .mode(fastHint.getMode())
                    .confidence(Math.max(0.5, fastHint.getConfidence()))
                    .source("fallback_fast")
                    .build();
        }
    }

    private String mapTag(String tag) {
        if (tag.contains("booking")) {
            return "booking";
        }
        if (tag.contains("policy")) {
            return "policy";
        }
        if (tag.contains("expense")) {
            return "expense";
        }
        if (tag.contains("rag")) {
            return "rag";
        }
        return "general";
    }

    @Data
    @Builder
    public static class IntentResult {
        private String mode;
        private double confidence;
        private String source;
        private String rawModelOutput;
    }
}
